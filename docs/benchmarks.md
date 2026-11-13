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
```

The table is printed and also written to `target/benchmark/results.md`. Tests tagged `benchmark` are excluded
from `mvn test` (`benchmark.excludedGroups` in `pom.xml`); `make bench` switches the exclusion off.

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
- Thread counts above the pool size would only queue for connections; keep them at or below 64.
- Short runs on a warm JVM; use `RUNS=` higher for steadier medians. Oversold/leaked/errors are the **worst** run,
  so one bad run cannot hide.
- `RedisGateCrashTest` wipes Redis repeatedly during a run and checks PostgreSQL still does not oversell. It is
  skipped until `RedisLuaGateStrategy` is implemented.

## Results and conclusion

_To be filled in after running `make bench` with all strategies implemented: paste the table, say which strategy
wins for seats and which for standing tickets and why, and record the choice in a new ADR._
