# ADR-0004: Refuse known sold-out requests with a short-lived Redis flag in front of the conditional UPDATE

## Status
Accepted

## Context
ADR-0001 and ADR-0003 take stock with one conditional `UPDATE` and check its row count. That is correct under any
interleaving, and the strategy benchmark (`docs/benchmarks.md`) shows it is the best database-only choice almost
everywhere. It has one weak spot: **a request that loses still pays for everything**. It borrows a pooled connection,
inserts an order, runs the `UPDATE`, reads the stock left for the error hint, and rolls back. When an event sells out,
most requests are exactly that, and they queue behind the connection pool and the hot row while doing no useful work.

The strategy benchmark also measured a Redis Lua gate (`redis-lua-gate`). It is about 3x faster when most requests
are refused and no better, sometimes worse, when stock is plentiful. It was benchmarked as a *counter*: Redis subtracts
first, PostgreSQL second, and the quantity is given back if PostgreSQL says no. A counter like that has failure modes
a refusal-only design does not have:

- if the process dies between the Redis subtraction and the database write, the counter stays short and customers are
  refused for ever, unless a reconciliation job repairs it;
- a missing key (restart, flush) must be treated as "unknown", not as zero (the benchmark's `GA_GATE` script answers
  "sold out" for a missing key);
- reconciling by comparing the two numbers is not safe, because requests in flight make them differ all the time;
- every failure path must hand the quantity back.

Rules that apply (CLAUDE.md): PostgreSQL is the source of truth; Redis may only hold cache, fast-reject gates and
counters, never the only record of a sale; no external I/O inside a transaction.

## Decision
A gate that **can only refuse**, in front of step 1 of `BookingService.bookTicket`, as flags with a short TTL.

- **Check before the transaction.** `rejectIfKnownUnavailable` asks `BookingGateway` first. A flagged request is
  refused with the same error the database would give (`ZONE_SOLD_OUT`, `SEAT_UNAVAILABLE` with the flagged
  `seatPublicIds`) before any connection is borrowed and before an order exists.
  *Changed since:* a refused request now makes one read, to return the order it already made when it is a repeat with the
  same `Idempotency-Key`. The figures below were measured without that read.
- **Write only after the database said no.** When `reserve` throws `ZONE_SOLD_OUT` or `SEAT_UNAVAILABLE`,
  `rememberRejection` sets the flag and rethrows. A successful request writes nothing.
- **Standing zones: the flag is the refused quantity.** Key `bidtix-backend:gate:zone:{zonePublicId}`, value N.
  A request for N or more is refused; a request for fewer still goes to the database (one ticket may be left when
  four were refused). The database does not return the stock left when its `UPDATE` fails, so the quantity asked is
  the only thing known for certain.
- **Seats: one key per seat and zone.** `bidtix-backend:gate:seat:{zonePublicId}:{seatPublicId}`, checked with one
  `MGET` for the whole order (so the response can name the flagged seats). The zone is part of the key so that a request
  naming a seat with the wrong zone cannot flag that seat in its real zone.
- **TTL 3 seconds** (`bidtix.booking.gate-ttl-seconds`). Flags expire by themselves; nothing reconciles them.
- **Fail open.** Redis down, a timeout or an unreadable value lets the request through (logged), and a failed flag write
  is swallowed. This is the opposite of the login/register rate limiters (fail-closed): here Redis is an optimisation,
  not a protection.
- **`bidtix.booking.gate-enabled`** switches it off, which is also how the benchmark compares the two.
- No Lua script is needed: the gate only reads, and the writes are single `SET key value EX`. There is no
  read-then-write step that needs to be atomic, and a last-writer-wins race on a quantity flag only makes the gate
  refuse less than it could, never more than the database did.

### Alternatives considered
| Option | Why not |
|---|---|
| Nothing (conditional UPDATE only) | Correct, and best when stock is plentiful, but every refused request still costs a connection, an order insert and a rollback. |
| Redis counter gate as benchmarked (`redis-lua-gate`) | Needs hand-back on every failure path, a repair job that is hard to make safe while requests are in flight, and lazy reloading of a missing key. A crash leaves refusals that do not heal. The flag heals within the TTL. |
| Redis as the authority, orders written to PostgreSQL later through a queue (flash-sale style) | Throughput is higher but a sale then exists only in Redis for a while, which CLAUDE.md forbids, and it needs a queue and reconciliation. |
| Admission control only (waiting room, rate limits) | Complementary, not a replacement. The per-user token bucket already exists; it limits how many requests arrive, not how much work each refused request does. |
| Cache the stock for reads and refuse from the cache | A cached number would have to be kept in step with every sale, which is the counter problem again. |

## Consequences
**Gains** (`make bench-gate`, `docs/benchmarks.md`, full `bookTicket` flow, 500 users, pool of 10 connections)
- Sold-out standing zone: 969 to 2,301 ops/s (2.4x), median latency 479 ms to 4.6 ms; 4,064 of 4,967 refusals were
  answered without touching PostgreSQL.
- One hot seat: 2,027 to 6,976 ops/s (3.4x).
- Plentiful stock: no gain (757 vs 717 ops/s, one median of three runs; read as "no gain"). The gate costs one Redis
  read per request there.
- Correctness is unaffected, by construction (the gate never grants) and by measurement: no oversell, orders equal sales,
  no unexpected errors, **0 false rejections** with the gate on. `BookingGatewayTest`, `BookingGateIntegrationTest` and
  `BookingGateRedisDownTest` cover the boundary (a flag of N refuses N and above, not below), expiry, zone scoping and
  Redis down. They were checked to go red when the boundary is reversed or the gate is not called.

**Costs and caveats**
- **A flag can be stale.** After stock comes back, a customer can still be refused for up to the TTL (3 s). This
  is the price of not having a counter to repair; it is bounded and heals without a job. The benchmark cannot show it,
  because nothing returns stock yet.
- A gate refusal of a standing zone has no `availableQuantity` hint in the response, unlike the database's.
- A refusal that the database gives for a seat the zone does not contain (a made-up or wrong-zone id) also creates a
  flag for 3 seconds. It is scoped to the wrong zone, so it never blocks the real seat; it is still a write per
  made-up id, which the rate limiter bounds.
- The benchmark's gain is partly the small pool: a refused request that skips the transaction also skips the wait for a
  connection. With a larger pool the difference would be smaller; not measured. Redis ran on the same machine, so its
  round trip was close to free. All users started at the same instant, so the first wave reached the database before any
  flag existed (903 database refusals in the sold-out case); a ramping sale would favour the gate more.

**Rules this creates**
- **Whatever returns stock must clear the matching flags after its database commit**: the hold-expiry sweeper (seat flags
  of the released seats, the zone flag when GA quantity is given back), a payment failure that releases stock, and an event
  cancel. Clear after the commit, never before: clearing first would let a request refill the flag from a state the
  database has not changed yet.
- A new kind of refusal that depends on stock must go through `rememberRejection`, or it is simply not gated.

**Not covered yet**
- The sweeper does not exist, so the clearing rule is untested and the stale window is unmeasured. When it is built,
  measure the window and add a test that releases stock and expects the next request to succeed at once.
- Redis down is tested for correctness, not for latency: a Redis that hangs (not refuses) costs the client timeout on
  every request unless a short command timeout or a circuit breaker is configured.
- No metric yet for flag hits and Redis errors; the log is the only signal.
