<p align="center">
  <img src="docs/images/logo.svg" alt="BidTix: a ticket with a seat map and an auction gavel coming down on it" width="420">
</p>

<p align="center">
  <a href="https://github.com/tqha1011/BidTix/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/tqha1011/BidTix/actions/workflows/ci.yml/badge.svg"></a>
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white">
  <img alt="Spring Boot 4" src="https://img.shields.io/badge/Spring%20Boot-4-6DB33F?logo=springboot&logoColor=white">
  <img alt="PostgreSQL" src="https://img.shields.io/badge/PostgreSQL-source%20of%20truth-4169E1?logo=postgresql&logoColor=white">
  <img alt="Redis" src="https://img.shields.io/badge/Redis-cache%20%26%20gate-DC382D?logo=redis&logoColor=white">
  <img alt="Testcontainers" src="https://img.shields.io/badge/tests-Testcontainers-9B59B6">
  <img alt="Status: work in progress" src="https://img.shields.io/badge/status-work%20in%20progress-f59e0b">
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-blue"></a>
</p>

# BidTix

A concert ticketing backend with two ways to buy a ticket: **fixed-price booking** (numbered seats and
standing zones) and **English auctions** for premium tickets.

The goal is to get the hard backend problems right rather than to build a big feature list:
no overselling, no double-sold seats, no lost bids, safe client retries, short and correct transactions,
and caching that never decides who gets a ticket.

> **Status: work in progress.** Booking, auctions and VNPAY payments work end to end and are tested against a
> real PostgreSQL and Redis. Payments were also run against the VNPAY sandbox; refunds are not proven there yet.
> Still missing: cancelling an event, a per-user ticket limit and ticket check-in. See [Roadmap](#roadmap).

## Two ways to buy a ticket

Both end the same way: an order with a payment deadline, a VNPAY payment, and a ticket once VNPAY confirms it.

**Booking, for a fixed price**

1. A customer picks seats, or a number of standing tickets. The stock is taken at once and the order waits for
   payment.
2. The customer pays on VNPAY's page. VNPAY's callback marks the order paid and issues the tickets.
3. An order nobody pays is cancelled after its deadline and the stock goes back on sale.

**Auction, for a single premium ticket**

1. The organizer auctions one seat or one standing ticket, which leaves normal sale. The auction opens at its start
   time.
2. Customers bid at least the minimum increment above the current price. A bid in the last seconds pushes the end
   back (anti-sniping).
3. At the end the highest bidder gets a deadline to pay, if the bids reached the hidden reserve price.
4. If they do not pay, the offer passes to the next-highest bidder at that bidder's own price. With nobody left, the
   auction fails and the ticket goes back on sale.

A payment that arrives for an order that can no longer take it (cancelled, already paid, offer gone) is marked for
refund. An expired order or offer with a payment still unconfirmed is kept 10 minutes longer, so a late callback
does not punish a customer who paid in time.

## What it demonstrates

| Problem | Approach | State |
|---|---|---|
| Two people buy the last seat | Conditional `UPDATE ... WHERE status = 'AVAILABLE'` + affected-row check; partial unique index on active tickets as the last line of defence ([ADR-0001](docs/ADR/0001-hold-seats-with-a-conditional-update.md)) | **Done** (30 parallel users, 1 seat: 1 wins; 60 users on 10 seats: no seat sold twice, no deadlock) |
| Overselling standing tickets | Atomic `UPDATE ... SET available = available - n WHERE available >= n` + row count + `CHECK (available BETWEEN 0 AND capacity)` ([ADR-0003](docs/ADR/0003-take-general-admission-stock-with-a-conditional-update.md)) | **Done**, concurrency test |
| Concurrent bids | One conditional `UPDATE` on the auction row decides status, deadline and minimum price, and extends the end for a late bid; `UNIQUE (auction_id, amount)` | **Done** (12 parallel bids: the highest leads; same amount: exactly 1 accepted) |
| An auction ends, the winner does not pay | Jobs close auctions and pass the offer on, one transaction each, rows taken with `FOR UPDATE SKIP LOCKED`; paying and expiring race on the same order row, so one wins | **Done** |
| Payment results arrive late, twice, or for a dead order | Booking only reserves; VNPAY's signed callback applies the money once (conditional `UPDATE` on the payment), stores every authentic callback, and marks money that cannot be applied for refund ([ADR-0005](docs/ADR/0005-confirm-vnpay-payments-in-the-ipn-callback.md)) | **Done** (8 identical callbacks in parallel: paid once) |
| A refund request that may or may not have arrived | A claim is committed before the call; it is given back only when the request certainly never left, so nothing is refunded twice | Done against a stand-in, not proven on the sandbox |
| Client retries a request | `Idempotency-Key` header, request hash, replayed response | **Done** for orders, payments and bids |
| Most requests lose once an event sells out | A short-lived Redis flag refuses requests already known to fail, before any transaction opens. It can only refuse: a missing flag or Redis down lets the request through and the conditional `UPDATE` decides ([ADR-0004](docs/ADR/0004-refuse-known-sold-out-requests-with-a-redis-flag.md)) | **Done**, measured in [docs/benchmarks.md](docs/benchmarks.md) |
| Unpaid orders keep stock locked, across several instances | `@Scheduled` job: batches of expired orders locked with `SELECT ... FOR UPDATE SKIP LOCKED`, stock returned in the same transaction, gate flags cleared after the commit | **Done** |
| Hot reads | Redis cache-aside with explicit TTLs, evicted on write; locking cache writer so 16 simultaneous misses cause one database load | **Done** (venue, published event list) |
| Stolen refresh token | Rotation + reuse detection; parallel use of one token decided by a conditional `UPDATE` (verified with 10 simultaneous requests: 1 wins, 9 rejected) | **Done** |
| Abuse and floods | Redis rate limits: fixed window per IP for login and register, token bucket per logged-in user; fail-closed for auth | **Done** |
| Consistent error responses | RFC 9457 `ProblemDetail` + stable error codes, also for 401/403 raised inside security filters | **Done** |

## Architecture

BidTix is a **modular monolith**: one deployable Spring Boot application and one PostgreSQL database,
split into business modules by feature (package-by-feature). Inside each module the code is a classic
**layered architecture**.

```
                      one Spring Boot application, one PostgreSQL database
 ┌──────────────────────────────────────────────────────────────────────────────────┐
 │  auth     user     event     booking     auction     payment     │   common      │
 │  (login,  (entity) (manage,  (seats,     (bids,      (VNPAY,     │   config      │
 │  tokens)           browse)   standing,   closing,    callback,   │  (shared)     │
 │                              orders,     second      refunds)    │               │
 │                              tickets)    chance)                 │               │
 │   each module:   controller ──► service ──► repository ──► DB                    │
 │                  (HTTP, DTOs)  (@Transactional, rules)   (Spring Data)           │
 │                                    │                                             │
 │                                    └──► domain (JPA entities, enums)             │
 └──────────────────────────────────────────────────────────────────────────────────┘
        PostgreSQL = source of truth.  Redis = cache, booking gate, leaderboard, rate limits.

 Request path:  JWT security filters ─► controller ─► service ─► repository ─► PostgreSQL
 Errors:        any layer throws ─► GlobalExceptionHandler ─► one JSON error format
```

Why a modular monolith: the problems this project is about (inventory, locking, transactions) are easiest
to get right and to test with one transactional database. Module boundaries keep the code ready to split
later without paying the cost of distributed transactions now.

Honest limits: this is *not* hexagonal/clean architecture (entities are JPA entities, services call Spring
Data repositories directly), and module boundaries are a convention that tests do not enforce yet.
Entities in different modules reference each other by id only, never through JPA relations.

## Tech stack

Java 21 · Spring Boot 4 (Web MVC, Data JPA, Security, OAuth2 Resource Server, Actuator) · PostgreSQL ·
Flyway · Redis (cache, booking gate, auction leaderboard, rate limiting) · VNPAY · Lombok · springdoc-openapi ·
JUnit 5, Mockito, MockMvc, Testcontainers (PostgreSQL and Redis).

## Getting started

Requirements: JDK 21 and Docker (Spring Boot starts PostgreSQL and Redis from `compose.yaml` automatically, and
the integration tests start their own containers).

```bash
# 1. Create the JWT signing keys (git-ignored, needed once)
mkdir -p keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/private.pem
openssl pkey -in keys/private.pem -pubout -out keys/public.pem

# 2. Run (the refresh cookie is "Secure"; switch that off only for plain-http local testing)
BIDTIX_AUTH_REFRESHCOOKIE_SECURE=false ./mvnw spring-boot:run
```

Shortcut for both steps: `make run-local`. Run `make help` to see all commands (`make test`,
`make test-one T=AuthServiceTest`, `make psql`, `make bench`, ...).

Swagger UI: <http://localhost:8080/swagger-ui/index.html> · OpenAPI JSON: `/v3/api-docs`

**Sample data (dev only):** `make run-seed` starts the app and, on an empty database, creates `admin@bidtix.dev`,
`organizer@bidtix.dev` and `customer1..3@bidtix.dev` (all with password `Password123!`), two venues, two published
events with seated and standing zones, and six paid tickets. Running it again does nothing. Never enable it in production.

**Payments:** set `VNP_TMNCODE` and `VNP_HASH_SECRET` from a VNPAY sandbox account as environment variables of the
app (its run configuration, or `export` in the shell; the app does not read `.env` by itself). The IPN URL
`.../api/v1/payments/vnpay/ipn` is configured on VNPAY's side and must be public and HTTPS, so local testing needs a
tunnel. The refund jobs run every minute; `bidtix.payment.refund-cron: "-"` turns them off.

## API

Every route needs a Bearer token except `/api/v1/auth/**`, the two VNPAY routes and the API docs. A wrong role
answers `403 FORBIDDEN`. Lists take `page` and `size`.

### Accounts

| Endpoint | Result |
|---|---|
| `POST /api/v1/auth/register` | `201`, creates a `CUSTOMER` (the role cannot be chosen by the client) |
| `POST /api/v1/auth/login` | access JWT (RS256, 5 min) in the body, refresh token in an `HttpOnly; SameSite=Strict` cookie |
| `POST /api/v1/auth/refresh` | new access token and a rotated refresh cookie; `401` if missing, expired or already used |
| `POST /api/v1/auth/logout` | `204`, always, and the cookie is cleared |

```bash
B=http://localhost:8080/api/v1/auth
curl -X POST $B/register -H 'Content-Type: application/json' \
     -d '{"email":"alice@example.com","password":"password123","fullName":"Alice"}'

# the refresh token is stored in the cookie jar, never shown in the response body
curl -c jar.txt -X POST $B/login -H 'Content-Type: application/json' \
     -d '{"email":"alice@example.com","password":"password123"}'

curl -b jar.txt -c jar.txt -X POST $B/refresh      # rotates the refresh token
curl -b jar.txt -c jar.txt -X POST $B/logout       # revokes it and clears the cookie
```

### Browsing (any logged-in user)

| Endpoint | Result |
|---|---|
| `GET /api/v1/events` | published events that have not started, soonest first (cached) |
| `GET /api/v1/events/{eventPublicId}` | zones, prices and what is left |
| `GET /api/v1/events/{eventPublicId}/zones/{zonePublicId}/seats` | seat map of a seated zone |
| `GET /api/v1/venues`, `GET /api/v1/venues/{venuePublicId}` | venues (one venue is cached) |
| `GET /api/v1/auctions?status=&eventPublicId=` | auctions, soonest ending first |
| `GET /api/v1/auctions/{auctionPublicId}`, `.../bids`, `.../leaderboard` | one auction, its bids, its top bidders |

### Buying (CUSTOMER)

| Endpoint | Result |
|---|---|
| `POST /api/v1/orders` | `201`, takes seats or standing tickets; `409` when gone or the sale is closed |
| `POST /api/v1/auctions/{auctionPublicId}/bids` | `201`, a bid of `{"amount": ...}` |
| `POST /api/v1/orders/{orderCode}/payments` | `201`, the VNPAY page for a booking or auction order |
| `GET /api/v1/orders`, `GET /api/v1/orders/{orderCode}` | own orders |
| `GET /api/v1/tickets` | own tickets; a ticket's public id is its QR payload |

The three `POST` routes need an `Idempotency-Key` header: a retry with the same key replays the first answer.

VNPAY calls `GET /api/v1/payments/vnpay/ipn` (this is what records a payment) and sends the customer's browser to
`GET /api/v1/payments/vnpay/return` (this only shows the state).

### Managing

| Endpoint | Role | Result |
|---|---|---|
| `POST /api/v1/venues` | ADMIN | a venue |
| `PUT` / `DELETE /api/v1/venues/{venuePublicId}` | ADMIN | `409 VENUE_IN_USE` when deleting a used venue |
| `GET /api/v1/admin/events/{eventPublicId}/tickets?status=` | ADMIN | issued tickets |
| `GET /api/v1/events/mine` | ORGANIZER | own events, drafts included |
| `POST /api/v1/events` | ORGANIZER | a `DRAFT` event |
| `POST /api/v1/events/{eventPublicId}/zones` | owner | a zone; a seated one gets all its seats from `rows` |
| `PUT` / `DELETE /api/v1/events/{eventPublicId}/zones/{zonePublicId}` | owner | drafts only |
| `POST /api/v1/events/{eventPublicId}/publish` | owner | `DRAFT` to `PUBLISHED`; needs a zone |
| `PUT /api/v1/events/{eventPublicId}` | owner | a draft entirely, a published event only title and description |
| `DELETE /api/v1/events/{eventPublicId}` | owner | a draft without orders |
| `POST /api/v1/auctions` | ORGANIZER | auctions one ticket of an own published event |
| `POST /api/v1/auctions/{auctionPublicId}/cancel` | owner | only while nobody has bid |

Only customers can register; `ADMIN` and `ORGANIZER` users are created by the seed or by SQL.

### Limits and errors

Rate limits: login 5 per minute and register 3 per minute per IP (`429 TOO_MANY_REQUESTS` with `Retry-After`);
other logged-in calls share a bucket of 15 requests refilled by 3 every 10 s; the VNPAY routes have their own wide
limits. Details in `bidtix.rate-limit.policies`.

Errors look like this, and clients should branch on `code`:

```json
{ "status": 409, "title": "Conflict", "detail": "Email is already registered", "code": "EMAIL_ALREADY_EXISTS" }
```

## Tests

```bash
./mvnw test                                  # everything (Docker must be running)
./mvnw test -Dtest=BidConcurrencyTest        # one class
```

Integration tests extend `PostgresIntegrationTest` and run the whole application against a real PostgreSQL and
Redis started by Testcontainers, with Flyway building the schema. The flows where concurrency matters fire parallel
requests and assert what must stay true:

- **Seats:** 30 users race for one seat, exactly one wins; 60 users ask for random seats out of 10, no deadlock and
  no seat sold twice.
- **Standing tickets:** the counter never goes negative, never loses an update and equals capacity minus tickets sold.
- **Auctions:** parallel bids leave the highest one leading; creating auctions for one seat succeeds once; a bid and a
  cancel never both win.
- **Payments:** identical callbacks pay once; two payments of one order give one success and one refund.

VNPAY is replaced by tests that sign their own callbacks and by a local stand-in for its refund and query API.

Throughput is measured separately and is tagged `benchmark`, so `make test` skips it:

```bash
make bench          # five anti-overselling strategies compared
make bench-gate     # the booking gate on the real booking flow, on and off
make bench-cancel   # cancelling unpaid orders, 1 vs many orders per transaction
```

The whole application can be load tested over HTTP with k6 (`Dockerfile` and `loadtest/`):

```bash
make load-test      # requests per second and users at once, with p95 < 500 ms and < 1% failures
make sale-day       # 30 minutes of people logging in, buying, paying and bidding, then 15 data checks
```

With the application and PostgreSQL limited to 2 CPUs each on one laptop, it held 600 requests/s of 90% reads and 10%
bookings (p95 11 ms), 200 bookings/s once warm (p95 27 ms) and 1,000 users at once (p95 239 ms), with no failed
request. A just-started instance carries much less until the JVM has warmed up.

In a 30-minute simulated sale (up to 1,000 shoppers and 150 bidders, 259,006 requests), p95 stayed between 7 and 24 ms
outside the first minute and the 380 ms minute in which the sale opened; the only 41 failed requests came in the first
minute, right after start-up, and every data check passed.

Method, numbers and caveats: [`docs/benchmarks.md`](docs/benchmarks.md).

## Documentation

- [`docs/database-schema.md`](docs/database-schema.md): tables, constraints, and the concurrency strategy
- [`docs/ADR/`](docs/ADR): architecture decision records
  - [ADR-0001](docs/ADR/0001-hold-seats-with-a-conditional-update.md): hold seats with a conditional UPDATE
  - [ADR-0002](docs/ADR/0002-take-payment-outside-the-transaction.md): take payment outside the transaction (superseded by ADR-0005)
  - [ADR-0003](docs/ADR/0003-take-general-admission-stock-with-a-conditional-update.md): take standing-zone stock with a conditional UPDATE
  - [ADR-0004](docs/ADR/0004-refuse-known-sold-out-requests-with-a-redis-flag.md): refuse known sold-out requests with a short-lived Redis flag
  - [ADR-0005](docs/ADR/0005-confirm-vnpay-payments-in-the-ipn-callback.md): confirm VNPAY payments in the IPN callback
- [`docs/benchmarks.md`](docs/benchmarks.md): anti-overselling strategies, the booking gate, order cancellation and the load test, measured
- [`CLAUDE.md`](CLAUDE.md): conventions and rules for working in this repository

## Roadmap

Done:

- [x] Authentication with rotating refresh tokens, roles, one error format
- [x] Venues, events, zones and seats: create, read, change, publish
- [x] Booking of seats and standing tickets, cancellation of unpaid orders, orders and tickets of a customer
- [x] Auctions: bidding, anti-sniping, closing, second-chance offers, leaderboard, cancelling unbid ones
- [x] VNPAY: payment page, idempotent callback, grace period, return endpoint
- [x] Refund job with retries and confirmation by querydr (against a stand-in)
- [x] Idempotency keys, Redis cache, rate limits, booking gate
- [x] Concurrency tests and [benchmarks](docs/benchmarks.md)

Next:

- [ ] Refunds proven on the VNPAY sandbox; reconciling payments left pending
- [ ] Cancelling an event (cache, gate flags, refunds)
- [ ] Per-user ticket limit and ticket check-in
- [ ] Reproduce overselling with a naive flow, then fix it (ADR)
- [ ] Observability: a secured `/actuator/prometheus` (only `health` is exposed today)

## License

MIT, see [`LICENSE`](LICENSE).
