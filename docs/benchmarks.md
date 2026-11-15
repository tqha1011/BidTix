# Anti-overselling benchmark

Compares five ways of making sure a seat or a standing ticket is sold only once, under concurrent load, on a
real PostgreSQL (Testcontainers). The question is not only "is it correct" (the other concurrency tests answer
that) but "what does correctness cost": throughput, tail latency, retries, false rejections.

## Run it

```bash
make test                       # fast: also runs the benchmark's own self-tests (no benchmark)
make bench                      # the full matrix, slow; needs Docker
make bench ONLY=optimistic      # only strategies whose name contains the text
make bench RUNS=3               # measured runs per cell (default 5, plus one discarded warm-up)
make bench THREADS=500          # simulated users, comma list (default 8,32,64)
make bench SCALE=5              # multiply requests and inventory (see below)
make bench VIRTUAL=true         # run the simulated users on Java 21 virtual threads instead of platform threads
```

The table is printed and also written to `target/benchmark/results-platform.md` (or `results-virtual.md` with
`VIRTUAL=true`, so the two runs do not overwrite each other). Tests tagged `benchmark` are excluded from
`mvn test` (`benchmark.excludedGroups` in `pom.xml`); `make bench` switches the exclusion off.

`SCALE=n` multiplies the number of requests, and the inventory by the same factor, so a run with many threads
still has enough work for all of them (`seat-hot` keeps its single seat: only its requests grow). "Virtual" only
changes how the **load generator** runs: the strategies call JDBC and Lettuce directly and there is no HTTP
server in this benchmark, so `spring.threads.virtual.enabled` would change nothing here.

## What is compared

The strategies live in `src/test/java/com/bidtix/benchmark/strategies/`. Each implements `HoldStrategy`
(`holdSeats` for numbered seats, `takeQuantity` for the standing counter). A method that still throws
`UnsupportedOperationException` is listed under "Not implemented yet" instead of failing the run.

| Strategy | Idea |
|---|---|
| `naive` | read, compare in Java, write. Expected to oversell; the baseline that shows the problem. |
| `conditional-update` | one `UPDATE ... WHERE <still free>` and check the row count (what the application does, ADR-0001/0003). |
| `optimistic` | `@Version`; the loser of a race retries a bounded number of times. |
| `pessimistic` | `SELECT ... FOR UPDATE`, then check and write. |
| `redis-lua-gate` | a Lua script decides atomically in Redis first; PostgreSQL stays the source of truth and still decides for real. |

Only the **inventory step** is measured, not payment or order creation, so the cost of the concurrency control
is not hidden by work every strategy does equally. Every simulated customer already has a `PENDING_PAYMENT` order
(`seats.held_by_order_id` has a foreign key to it).

## Scenarios (each at 8, 32 and 64 threads)

| Scenario | Shape | What it stresses |
|---|---|---|
| `seat-hot` | 1 seat, 200 customers | all contention on one row |
| `seat-spread` | 200 seats, 400 customers wanting 2-3 seats in random order | overlap and deadlocks |
| `ga-hot` | capacity 500, 600 customers wanting 1-4 | one hot counter row; where strategies differ most |
| `ga-soldout-tail` | capacity 50, 1000 customers wanting 1-2 | after the stock is gone, every request is a rejection |

The number of requests is fixed per scenario, so changing the thread count only changes the concurrency.
Every strategy gets exactly the same requests (fixed seed).

## Reading the table

| Column | Meaning |
|---|---|
| held / sold out | requests answered "yours" / "no stock". Both are correct answers. |
| gave up | the strategy stopped retrying although stock may remain: a **false rejection** (optimistic locking) |
| errors | deadlocks, timeouts, anything unexpected; the "Errors" section below the table lists the kinds |
| retries | total retries over all requests |
| **oversold** | clients were promised stock that PostgreSQL does not record for them. **Must be 0 for every strategy except `naive`**; the test fails otherwise. |
| leaked | PostgreSQL records stock as taken that nobody was promised (a rollback or compensation that did not happen). Not an oversell, but stock lost. Also must be 0. |
| ops/s, p50/p95/p99 | throughput and latency per request; median over the measured runs |

"Oversold" is judged against the database after the run, never against what the strategies said:
seats must be held by the very order that was told it won them; for the counter, tickets promised must equal
`capacity - available_quantity`.

## Caveats

- Absolute numbers are PostgreSQL in Docker on one machine, with a connection pool of 64 (printed in the report).
  Only the relative order of the strategies means something.
- Thread counts above the pool size only queue for connections. At 500 threads and a pool of 64, the latency of
  every strategy includes the wait for a connection, and the pool (not the thread type) is the first bottleneck.
  That is closer to an application with many concurrent users and a small pool than to 500 database sessions.
- Short runs on a warm JVM; use `RUNS=` higher for steadier medians. Oversold/leaked/errors are the **worst** run,
  so one bad run cannot hide.
- The load generator, PostgreSQL and Redis all run on the same machine, so the network cost of Redis is close to
  zero here; with a remote Redis the gate would cost more per request than these numbers show.
- `RedisGateCrashTest` wipes Redis repeatedly during a run and checks PostgreSQL still does not oversell.

## Results and conclusion

Run of 2026-11-15: `make bench THREADS=500 SCALE=5 RUNS=3` (platform threads) and the same with `VIRTUAL=true`.
500 simulated users, pool of 64, PostgreSQL 17.11 in Docker, 12 cores, JVM 21. Per cell: 1 warm-up run
(discarded) + 3 measured runs; ops/s and latency are the median, oversold/leaked/errors the worst run.
Requests per scenario: `seat-hot` 1,000 (1 seat), `seat-spread` 2,000 (1,000 seats), `ga-hot` 3,000 (capacity
2,500), `ga-soldout-tail` 5,000 (capacity 250).

### Platform threads

| strategy | scenario | held | sold out | gave up | errors | retries | **oversold** | leaked | ops/s | p50 ms | p95 ms | p99 ms |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| naive | seat-hot | 1000 | 0 | 0 | 0 | 0 | **999** | 0 | 1270 | 259.80 | 507.38 | 575.45 |
| naive | seat-spread | 2000 | 0 | 0 | 0 | 0 | **4038** | 0 | 10696 | 9.98 | 79.43 | 103.55 |
| naive | ga-hot | 3000 | 0 | 0 | 0 | 0 | **7414** | 0 | 5694 | 14.61 | 386.24 | 482.04 |
| naive | ga-soldout-tail | 5000 | 0 | 0 | 0 | 0 | **7422** | 0 | 5632 | 13.12 | 465.62 | 778.67 |
| conditional-update | seat-hot | 1 | 999 | 0 | 0 | 0 | **0** | 0 | 8719 | 6.62 | 29.50 | 49.91 |
| conditional-update | seat-spread | 322 | 1678 | 0 | 0 | 0 | **0** | 0 | 11861 | 7.31 | 61.39 | 88.18 |
| conditional-update | ga-hot | 1008 | 1992 | 0 | 0 | 0 | **0** | 0 | 7953 | 9.56 | 290.40 | 351.03 |
| conditional-update | ga-soldout-tail | 171 | 4829 | 0 | 0 | 0 | **0** | 0 | 21022 | 2.62 | 122.78 | 216.73 |
| optimistic | seat-hot | 1 | 999 | 0 | 0 | 40 | **0** | 0 | 16015 | 4.10 | 28.81 | 38.59 |
| optimistic | seat-spread | 321 | 1679 | 0 | 0 | 57 | **0** | 0 | 15951 | 5.53 | 50.64 | 68.06 |
| optimistic | ga-hot | 1017 | 1082 | 901 | 0 | 12119 | **0** | 0 | 1355 | 342.29 | 794.25 | 892.11 |
| optimistic | ga-soldout-tail | 166 | 4834 | 0 | 0 | 2095 | **0** | 0 | 10704 | 1.43 | 369.39 | 447.43 |
| pessimistic | seat-hot | 1 | 999 | 0 | 0 | 0 | **0** | 0 | 6315 | 64.70 | 127.43 | 135.51 |
| pessimistic | seat-spread | 316 | 1684 | 0 | 0 | 0 | **0** | 0 | 15180 | 6.79 | 73.89 | 91.69 |
| pessimistic | ga-hot | 1005 | 1995 | 0 | 0 | 0 | **0** | 0 | 4527 | 11.95 | 451.54 | 629.43 |
| pessimistic | ga-soldout-tail | 167 | 4833 | 0 | 0 | 0 | **0** | 0 | 7228 | 8.78 | 387.75 | 645.59 |
| redis-lua-gate | seat-hot | 1 | 999 | 0 | 0 | 0 | **0** | 0 | 42224 | 7.46 | 11.62 | 11.86 |
| redis-lua-gate | seat-spread | 320 | 1680 | 0 | 0 | 0 | **0** | 0 | 34013 | 10.87 | 25.60 | 36.51 |
| redis-lua-gate | ga-hot | 999 | 2001 | 0 | 0 | 0 | **0** | 0 | 10994 | 1.83 | 141.27 | 183.22 |
| redis-lua-gate | ga-soldout-tail | 172 | 4828 | 0 | 0 | 0 | **0** | 0 | 83879 | 4.12 | 7.95 | 38.72 |

### Virtual threads

| strategy | scenario | held | sold out | gave up | errors | retries | **oversold** | leaked | ops/s | p50 ms | p95 ms | p99 ms |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| naive | seat-hot | 1000 | 0 | 0 | 0 | 0 | **999** | 0 | 1215 | 318.95 | 486.04 | 561.25 |
| naive | seat-spread | 2000 | 0 | 0 | 0 | 0 | **4038** | 0 | 10560 | 11.31 | 162.93 | 184.34 |
| naive | ga-hot | 3000 | 0 | 0 | 0 | 0 | **7415** | 0 | 5456 | 50.71 | 282.16 | 429.65 |
| naive | ga-soldout-tail | 5000 | 0 | 0 | 0 | 0 | **7431** | 0 | 5442 | 45.85 | 325.53 | 486.92 |
| conditional-update | seat-hot | 1 | 999 | 0 | 0 | 0 | **0** | 0 | 9121 | 23.57 | 103.17 | 108.78 |
| conditional-update | seat-spread | 319 | 1681 | 0 | 0 | 0 | **0** | 0 | 10291 | 7.27 | 184.46 | 191.33 |
| conditional-update | ga-hot | 1014 | 1986 | 0 | 0 | 0 | **0** | 0 | 8084 | 8.61 | 236.59 | 357.51 |
| conditional-update | ga-soldout-tail | 173 | 4827 | 0 | 0 | 0 | **0** | 0 | 25180 | 1.93 | 137.13 | 192.05 |
| optimistic | seat-hot | 1 | 999 | 0 | 0 | 55 | **0** | 0 | 12929 | 23.51 | 71.67 | 74.51 |
| optimistic | seat-spread | 316 | 1684 | 0 | 0 | 59 | **0** | 0 | 16187 | 5.56 | 100.99 | 112.06 |
| optimistic | ga-hot | 997 | 1213 | 790 | 0 | 11349 | **0** | 0 | 1264 | 341.63 | 883.19 | 956.47 |
| optimistic | ga-soldout-tail | 166 | 4834 | 0 | 0 | 2001 | **0** | 0 | 11053 | 1.55 | 353.84 | 444.83 |
| pessimistic | seat-hot | 1 | 999 | 0 | 0 | 0 | **0** | 0 | 6058 | 74.62 | 144.28 | 155.71 |
| pessimistic | seat-spread | 322 | 1678 | 0 | 0 | 0 | **0** | 0 | 15770 | 5.65 | 113.30 | 125.14 |
| pessimistic | ga-hot | 1020 | 1980 | 0 | 0 | 0 | **0** | 0 | 4150 | 51.34 | 475.30 | 669.43 |
| pessimistic | ga-soldout-tail | 170 | 4830 | 0 | 0 | 0 | **0** | 0 | 7152 | 9.01 | 277.06 | 458.21 |
| redis-lua-gate | seat-hot | 1 | 999 | 0 | 0 | 0 | **0** | 0 | 30587 | 15.45 | 17.43 | 17.56 |
| redis-lua-gate | seat-spread | 327 | 1673 | 0 | 0 | 0 | **0** | 0 | 42135 | 8.80 | 33.02 | 38.28 |
| redis-lua-gate | ga-hot | 1012 | 1988 | 0 | 0 | 0 | **0** | 0 | 10284 | 1.51 | 153.09 | 190.43 |
| redis-lua-gate | ga-soldout-tail | 172 | 4828 | 0 | 0 | 0 | **0** | 0 | 56713 | 4.17 | 8.95 | 72.65 |

### What the numbers show

- **Correctness.** Every strategy except `naive` has oversold = 0, leaked = 0 and errors = 0 in both runs (no
  deadlocks, no lock or connection timeouts). `naive` oversells in every scenario (999 on the single hot seat,
  about 7,400 tickets on the counters), so the benchmark does detect the problem. `RedisGateCrashTest` passed.
- **Standing tickets (`ga-hot`, one hot row).** `redis-lua-gate` ~11.0k ops/s > `conditional-update` ~8.0k >
  `pessimistic` ~4.5k >> `optimistic` ~1.4k. Optimistic locking is the clear loser: with everybody changing the
  same row, 901 of 3,000 requests gave up after the retry limit although stock remained (a 30% false rejection
  rate) and the run needed 12,119 retries. When the stock is gone (`ga-soldout-tail`) the gate answers most
  requests without touching PostgreSQL: ~84k ops/s against ~21k for `conditional-update`.
- **Seats.** `redis-lua-gate` is fastest again (42k on `seat-hot`, 34k on `seat-spread`). Among the
  database-only strategies, `optimistic` was faster than `conditional-update` here (16.0k vs 8.7k on `seat-hot`,
  16.0k vs 11.9k on `seat-spread`) with 0 false rejections. A likely reason, not measured: a seat that is already
  HELD is rejected by a plain read, without waiting for the row lock, whereas the conditional `UPDATE` queues on
  the winner's transaction. `pessimistic` is the slowest on a single contended seat (6.3k, p50 65 ms: everyone
  queues on the lock) but is close to the others when contention is spread out.
- **Virtual threads.** The order of the strategies is the same as with platform threads, and there is no
  consistent throughput gain: the differences are mixed in sign and mostly within what 3 runs can tell apart
  (`redis-lua-gate` moved by about 25-30% in both directions between the two runs). The p95/p99 latency of the
  seat scenarios was higher with virtual threads for `conditional-update` and `optimistic` (for example
  `conditional-update` / `seat-spread` p95 61 ms to 184 ms). This fits a run limited by the 64-connection pool and
  PostgreSQL rather than by the thread type; the cause of the higher tail latency was not investigated (for
  example whether the PostgreSQL driver pins virtual threads).
- **Cost of the fastest option.** The gate is the fastest in every scenario, and PostgreSQL still decides
  (oversold = 0 even when Redis is wiped). It adds a component to operate and one failure mode: if the process
  dies between the gate taking stock and the release, a standing-ticket counter in Redis stays short until it is
  warmed again from the database (seat keys repair themselves through their TTL).

### Choice

Not made yet. These numbers support, at least: do not use optimistic locking for the standing-ticket counter, and
`naive` is not acceptable. Whether to keep `conditional-update` (ADR-0001 / ADR-0003) or add the Redis gate is a
decision to record in a new ADR, weighing the throughput above against the extra component and its failure mode.
