# Load test log

The raw record of every load test run on this project, in order: when it ran, on what, under which load, and what came
out, including the runs that failed. Method and conclusions are in [benchmarks.md](benchmarks.md).

## Machine and software

One laptop runs everything: the application, PostgreSQL, Redis and the load generator.

| | |
|---|---|
| CPU | 12th Gen Intel Core i5-12450HX: 8 cores (4 performance, 4 efficiency), 12 threads, up to 4.4 GHz |
| Memory | 23 GiB |
| OS | EndeavourOS (Linux 6.18.55-1-lts), power profile balanced-performance, on mains power |
| Docker | Engine 29.9.0, Compose 5.6.0 |
| Load generator | k6 2.0.0, on the host, calling the application container's address directly |
| Java | Temurin 21.0.13 (`eclipse-temurin:21-jre`), G1, `-XX:MaxRAMPercentage=75` |
| Database, cache | `postgres:17.11-alpine`, `redis:8.6.1-alpine` |

**The stack under test** (`loadtest/compose.yaml`), the same in every run unless a run says otherwise:

| container | CPU limit | memory limit | settings |
|---|--:|--:|---|
| application | 2 | 2 GB | Hikari pool 10, Tomcat defaults (200 threads, 8,192 connections), rate limiter on with limits nobody reaches, refund job off |
| PostgreSQL | 2 | 1 GB | defaults (from run 6 on, also logging of checkpoints, lock waits and statements over 1 s) |
| Redis | 1 | 256 MB | defaults |

Application code: commit `6dd5be7` for every run (the commits after it add only the image build, scripts and documents).
Scripts were still uncommitted during runs 1 to 9 (committed as `e8bdd7d` and `89ead81`), and the concurrency
profile was not yet committed during runs 10 to 12. Times are UTC+7.
In the tables, CPU is in percent of one core, so 200% means both of a container's cores are busy.

## 2026-11-25

### Run 1: first rehearsal, failed twice

- **When:** 22:08 to 22:09
- **Command:** `loadtest/run.sh smoke`
- **Load:** 20 req/s for 10 s per profile, no warm-up, on a stack that had just started

| run | target | req/s | p50 ms | p95 ms | p99 ms | failed | dropped | pass | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|
| read smoke | 20 | 20 | 16 | 1137 | 1502 | 0% | 0% | **no** | 95% | 6% | 1% | 6% | 63 MB |

Second attempt, same settings (stack kept running afterwards):

| run | target | req/s | p50 ms | p95 ms | p99 ms | failed | dropped | pass | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|
| read smoke | 20 | 20 | 17 | 975 | 1617 | 0% | 0% | **no** | 89% | 6% | 3% | 6% | 63 MB |

On that kept stack, the same 20 req/s read load three more times in a row:

| attempt | p50 ms | p95 ms | p99 ms | failed |
|--:|--:|--:|--:|--:|
| 1 | 11 | 17 | 20 | 0% |
| 2 | 10 | 15 | 17 | 0% |
| 3 | 8 | 14 | 15 | 0% |

**Notes:** The failures came from the cold JVM, not from the load. A 20 s warm-up was added to the smoke profile.

### Run 2: rehearsal, passed

- **When:** 22:10 to 22:11
- **Command:** `loadtest/run.sh smoke`
- **Load:** 20 s warm-up at 100 req/s, then 20 req/s for 10 s per profile and 100 quick requests from one user

| run | target | req/s | p50 ms | p95 ms | p99 ms | failed | dropped | pass | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|
| read smoke | 20 | 20 | 8 | 14 | 16 | 0% | 0% | yes | 60% | 2% | 2% | 6% | 70 MB |
| book smoke | 20 | 20 | 22 | 27 | 30 | 0% | 0% | yes | 51% | 10% | 2% | 6% | 64 MB |
| mixed smoke | 20 | 20 | 8 | 24 | 26 | 0% | 0% | yes | 24% | 2% | 3% | 8% | 68 MB |

100 quick requests from one user: none refused.

Stock check: 401 tickets taken from the hot zone, 401 in orders.

### Run 3: checking that overload is reported as failure

- **When:** 22:11 to 22:14
- **Command:** `RATES="50 20000" WARMUP=20 STEP=15 CONFIRM=15 COOLDOWN=5 loadtest/run.sh book`
- **Load:** bookings only; a rate far above what the stack can do, on purpose

#### Requests per second: book

| run | target | req/s | p50 ms | p95 ms | p99 ms | failed | dropped | pass | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|
| book ladder | 50 | 50 | 11 | 15 | 18 | 0% | 0% | yes | 110% | 13% | 2% | 6% | 71 MB |
| book ladder | 20000 | 474 | 10000 | 10001 | 10001 | 70.89% | 97.63% | **no** | 195% | 83% | 6% | 64% | 908 MB |
| book between | 10025 | 423 | 10000 | 10001 | 10002 | 70.96% | 95.78% | **no** | 183% | 132% | 10% | 62% | 840 MB |
| book confirm | 50 | 50 | 13 | 17 | 19 | 0% | 0% | yes | 34% | 17% | 3% | 10% | 71 MB |

Stock check: 16339 tickets taken from the hot zone, 16339 in orders.

**book: held 50 req/s for 15s (first failing step: 20000 req/s).**

**Notes:** The 20,000 req/s steps are meant to fail. Timeouts were 10 s.

### Run 4: rehearsal of the users test

- **When:** 22:14 to 22:16
- **Command:** `USER_LEVELS="200" WARMUP=20 CONFIRM=20 RAMP=5 COOLDOWN=5 loadtest/run.sh users`
- **Load:** 200 users, mixed traffic, 1-3 s between a user's requests

#### Users at once (mixed traffic, 1-3 s between a user's requests)

| run | target | req/s | p50 ms | p95 ms | p99 ms | failed | dropped | pass | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|
| mixed users | 200 | 99 | 5 | 19 | 27 | 0% | 0% | yes | 73% | 12% | 4% | 14% | 118 MB |

Stock check: 413 tickets taken from the hot zone, 413 in orders.

**Users at once: 200 held for 20s; first failing level: none.**

### Run 5: request rates and users at once

- **When:** 22:16 to 22:59 (43 min)
- **Command:** `make load-test`
- **Load:** profiles read (half cached event list, half uncached event detail), book (one standing ticket, one zone) and mixed (90% read, 10% book). Fixed arrival rate: 60 s warm-up at 100 req/s, 60 s per step, best step held 180 s. Users: 1,000 to 5,000 with 1-3 s between requests, 180 s each. Pass: p95 < 500 ms, failed < 1%, not sent on time < 1%. Data: 5,000 customers, 50 events, tokens signed beforehand

#### Requests per second: read

| run | target | req/s | p50 ms | p95 ms | p99 ms | failed | dropped | pass | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|
| read ladder | 100 | 100 | 4 | 8 | 9 | 0% | 0% | yes | 42% | 11% | 5% | 15% | 90 MB |
| read ladder | 200 | 200 | 3 | 7 | 9 | 0% | 0% | yes | 51% | 21% | 6% | 28% | 119 MB |
| read ladder | 400 | 400 | 3 | 7 | 10 | 0% | 0% | yes | 80% | 38% | 9% | 51% | 184 MB |
| read ladder | 800 | 781 | 881 | 2123 | 2701 | 0% | 2.42% | **no** | 186% | 100% | 8% | 101% | 563 MB |
| read between | 600 | 600 | 3 | 6 | 16 | 0% | 0% | yes | 106% | 53% | 11% | 73% | 248 MB |
| read confirm | 600 | 600 | 3 | 6 | 15 | 0% | 0% | yes | 106% | 53% | 11% | 70% | 266 MB |

**read: held 600 req/s for 180s (first failing step: 800 req/s).**

#### Requests per second: book

| run | target | req/s | p50 ms | p95 ms | p99 ms | failed | dropped | pass | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|
| book ladder | 100 | 100 | 10 | 16 | 19 | 0% | 0% | yes | 80% | 28% | 4% | 14% | 89 MB |
| book ladder | 200 | 200 | 10 | 17 | 211 | 0% | 0% | yes | 83% | 63% | 7% | 31% | 121 MB |
| book ladder | 400 | 385 | 1897 | 3367 | 3628 | 0% | 3.8% | **no** | 192% | 182% | 11% | 63% | 416 MB |
| book between | 300 | 283 | 12 | 5623 | 6274 | 0% | 5.78% | **no** | 114% | 93% | 6% | 44% | 364 MB |
| book confirm | 200 | 200 | 11 | 27 | 153 | 0% | 0% | yes | 85% | 67% | 6% | 31% | 124 MB |

Stock check: 99164 tickets taken from the hot zone, 99164 in orders.

**book: held 200 req/s for 180s (first failing step: 400 req/s).**

#### Requests per second: mixed

| run | target | req/s | p50 ms | p95 ms | p99 ms | failed | dropped | pass | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|
| mixed ladder | 100 | 100 | 5 | 17 | 21 | 0% | 0% | yes | 49% | 15% | 5% | 15% | 89 MB |
| mixed ladder | 200 | 200 | 4 | 13 | 17 | 0% | 0% | yes | 59% | 25% | 6% | 27% | 119 MB |
| mixed ladder | 400 | 400 | 3 | 12 | 15 | 0% | 0% | yes | 92% | 48% | 8% | 52% | 184 MB |
| mixed ladder | 800 | 650 | 4254 | 5642 | 6413 | 0% | 18.72% | **no** | 191% | 109% | 9% | 91% | 1036 MB |
| mixed between | 600 | 600 | 3 | 11 | 17 | 0% | 0% | yes | 116% | 66% | 11% | 73% | 249 MB |
| mixed confirm | 600 | 600 | 3 | 11 | 21 | 0% | 0% | yes | 116% | 67% | 11% | 71% | 266 MB |

Stock check: 23172 tickets taken from the hot zone, 23172 in orders.

**mixed: held 600 req/s for 180s (first failing step: 800 req/s).**

#### Users at once (mixed traffic, 1-3 s between a user's requests)

| run | target | req/s | p50 ms | p95 ms | p99 ms | failed | dropped | pass | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|
| mixed users | 1000 | 489 | 4 | 239 | 784 | 0% | 0% | yes | 136% | 67% | 10% | 63% | 397 MB |

Stock check: 9958 tickets taken from the hot zone, 9958 in orders.

| run | target | req/s | p50 ms | p95 ms | p99 ms | failed | dropped | pass | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|
| mixed users | 2000 | 539 | 1697 | 3192 | 4311 | 0% | 0% | **no** | 195% | 103% | 9% | 76% | 726 MB |

Stock check: 11274 tickets taken from the hot zone, 11274 in orders.

**Users at once: 1000 held for 180s; first failing level: 2000.**

**Notes:** 600 req/s is the highest rate that was tried and passed for read and mixed, not a measured maximum: the application used about 110% of its 200% CPU there. Runs 6 and 7 showed that results depend on how warm the JVM is, which may also explain failing steps here.

### Run 6: booking measured again, failed at once

- **When:** 23:14 to 23:17
- **Command:** `RATES="200 250 300 350 400" loadtest/run.sh book`
- **Load:** as the book profile of run 5, but the first step after the warm-up was 200 req/s (run 5 had a 100 req/s step in between). GC and PostgreSQL logging switched on

#### Requests per second: book

| run | target | req/s | p50 ms | p95 ms | p99 ms | failed | dropped | pass | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|
| book ladder | 200 | 183 | 4920 | 6678 | 7054 | 0% | 8.62% | **no** | 204% | 94% | 7% | 34% | 423 MB |

Stock check: 16124 tickets taken from the hot zone, 16124 in orders.

**book: no rate was held for 180s.**

**Notes:** In the logs: no ERROR, no statement over 1 s, no lock wait, garbage collections of at most about 110 ms. The application used both cores for the whole step.

### Run 7: is it the warm-up?

- **When:** about 23:19 to 23:27
- **Command:** `a one-off script (not in the repository) using loadtest/script.js`
- **Load:** one fresh stack, 60 s at 100 bookings/s, then 200/s five times in a row and 300/s once, 60 s each, 10 s apart

| attempt | p50 ms | p95 ms | not sent on time | passed | application CPU samples over the run |
|---|--:|--:|--:|---|---|
| warm-up, 100/s | 7,005 | 8,576 | 10.7% | no | 211 201 202 203 201 203 205 202 |
| 200/s, 1st | 13 | 2,839 | 2.8% | no | 220 195 183 151 130 87 81 |
| 200/s, 2nd | 11 | 32 | 0% | yes | 88 84 84 71 93 82 83 |
| 200/s, 3rd | 11 | 199 | 0% | yes | 87 84 90 91 81 81 77 |
| 200/s, 4th | 10 | 14 | 0% | yes | 80 88 76 83 77 75 89 |
| 200/s, 5th | 10 | 14 | 0% | yes | 77 81 80 90 78 80 80 |
| 300/s | 11 | 2,192 | 2.0% | no | 122 133 202 209 118 105 112 |

**Notes:** The same load goes from failing to passing without any change but time under load. The likely cause is the JIT compiler competing for the two cores; no compiler log was taken.

### Run 8: sale day rehearsal

- **When:** 23:27 to 23:32
- **Command:** `MINUTES=3 BASE=20 PEAK=40 BIDDERS=15 SETTLE=70 loadtest/soak.sh`
- **Load:** 3 minutes of the sale day scenario, to find mistakes in the script

- 3 minutes; shoppers 20, peak 40 when the sale opens at minute 0.3; 15 bidders

#### Minute by minute

CPU: 200% = both cores busy. Memory and orders are the last sample of the minute.

| minute | req/s | p95 ms | failed | app CPU | app MB | db CPU | lock waits (max) | orders paid | pending | cancelled |
|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 0 | 18 | 104 | 0% | 70% | 627 | 7% | 0 | 5 | 22 | 0 |
| 1 | 20 | 25 | 0% | 22% | 631 | 5% | 0 | 29 | 17 | 0 |
| 2 | 19 | 22 | 0% | 15% | 622 | 7% | 0 | 45 | 22 | 0 |
| 3 | 3 | 22 | 0% | 4% | 621 | 3% | 0 | 50 | 22 | 0 |

#### By endpoint

| endpoint | requests | p50 ms | p95 ms | p99 ms | max ms | failed |
|---|--:|--:|--:|--:|--:|--:|
| auctions | 87 | 15 | 294 | 391 | 406 | 0% |
| auction | 1523 | 7 | 12 | 23 | 189 | 0% |
| bid-history | 227 | 6 | 10 | 13 | 18 | 0% |
| bid | 398 | 20 | 29 | 41 | 273 | 0% |
| book | 71 | 28 | 61 | 258 | 259 | 0% |
| event-detail | 134 | 10 | 20 | 88 | 122 | 0% |
| event-list | 130 | 7 | 19 | 76 | 204 | 0% |
| ipn | 60 | 25 | 40 | 56 | 58 | 0% |
| leaderboard | 532 | 7 | 13 | 109 | 193 | 0% |
| login | 56 | 90 | 1129 | 1133 | 1137 | 0% |
| orders | 77 | 7 | 10 | 12 | 18 | 0% |
| order | 58 | 9 | 13 | 21 | 28 | 0% |
| pay | 60 | 19 | 30 | 120 | 231 | 0% |
| return | 58 | 6 | 9 | 11 | 11 | 0% |
| seat-map | 38 | 13 | 26 | 46 | 47 | 0% |
| tickets | 50 | 11 | 15 | 16 | 17 | 0% |

Total: 3559 requests, 17 req/s on average.

#### What happened

| | count |
|---|--:|
| bookings | 71 |
| payments ok | 50 |
| payments declined | 8 |
| duplicate ipn answered 02 | 2 |
| abandoned | 12 |
| seat conflicts | 0 |
| sold out | 0 |
| not on sale | 0 |
| bids accepted | 306 |
| bids outbid | 89 |
| bids too late | 3 |
| auction orders paid | 0 |
| auction orders ignored | 1 |
| paid but not applied | 0 |
| unexpected | 0 |

#### Data after the run

| check | found (must be 0) |
|---|--:|
| a seat with two live tickets | 0 |
| a paid order whose tickets do not match its lines | 0 |
| a ticket on an order that is not paid | 0 |
| a standing zone whose counter does not match orders and auctions | 0 |
| a sold seat without a ticket | 0 |
| a ticket on a seat that is not sold | 0 |
| a held seat without an unpaid order | 0 |
| a paid order without exactly one successful payment | 0 |
| a successful payment on an order that is not paid | 0 |
| an auction price that is not its highest bid | 0 |
| an auction whose bid count is wrong | 0 |
| a bid below the one before plus the increment | 0 |
| an auction still live 2 minutes after its end | 0 |
| an unpaid order 12 minutes past its deadline | 0 |
| a stored payment callback that was not processed | 0 |

- auction offers PENDING: 9
- auctions AWAITING_PAYMENT: 9
- auctions FAILED: 1
- bids total: 306
- orders PAID: 50
- orders PENDING_PAYMENT: 30
- payments FAILED: 8
- payments PENDING: 2
- payments SUCCEEDED: 50

#### Health

- app: status running, restarts 0, killed for memory false
- database: status running, restarts 0, killed for memory false
- cache: status running, restarts 0, killed for memory false
- application log: 0 ERROR and 2 WARN lines
- database log: 0 deadlocks, 0 lock waits over 1 s, 0 statements over 1 s
- garbage collection: 63 pauses, 0.5 s in total, longest 40 ms

Most frequent WARN and ERROR messages:

```
      1 WARN o.s.core.events.SpringDocAppInitializer  : SpringDoc /vN/api-docs endpoint is enabled by default. To disable it in production, set the property 'springdoc.api-docs.enabled=false'
      1 WARN o.s.core.events.SpringDocAppInitializer  : SpringDoc /swagger-ui.html endpoint is enabled by default. To disable it in production, set the property 'springdoc.swagger-ui.enabled=false'
```

### Run 9: sale day, 30 minutes

- **When:** 23:33 to 00:05 (30 min of load)
- **Command:** `make sale-day`
- **Load:** 300 shoppers, 1,000 from minute 3 to about 10, then 300; 150 bidders on 10 auctions; real logins; callbacks signed by the script; no warm-up (see benchmarks.md, "Sale day")

- 30 minutes; shoppers 300, peak 1000 when the sale opens at minute 3.0; 150 bidders

#### Minute by minute

CPU: 200% = both cores busy. Memory and orders are the last sample of the minute.

| minute | req/s | p95 ms | failed | app CPU | app MB | db CPU | lock waits (max) | orders paid | pending | cancelled |
|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 0 | 38 | 6652 | 1.79% | 119% | 613 | 7% | 0 | 2 | 12 | 0 |
| 1 | 97 | 18 | 0% | 86% | 627 | 11% | 0 | 40 | 46 | 0 |
| 2 | 105 | 15 | 0% | 49% | 631 | 16% | 0 | 117 | 80 | 0 |
| 3 | 173 | 380 | 0% | 134% | 634 | 14% | 0 | 337 | 383 | 0 |
| 4 | 209 | 18 | 0% | 103% | 636 | 28% | 0 | 1001 | 649 | 0 |
| 5 | 231 | 24 | 0% | 89% | 633 | 33% | 0 | 1650 | 793 | 0 |
| 6 | 225 | 13 | 0% | 66% | 635 | 31% | 0 | 2406 | 1014 | 0 |
| 7 | 216 | 12 | 0% | 64% | 636 | 26% | 0 | 2937 | 955 | 0 |
| 8 | 220 | 12 | 0% | 58% | 636 | 25% | 0 | 3203 | 1035 | 0 |
| 9 | 212 | 12 | 0% | 51% | 637 | 23% | 0 | 3555 | 1142 | 0 |
| 10 | 169 | 12 | 0% | 49% | 636 | 18% | 0 | 3834 | 1117 | 0 |
| 11 | 138 | 10 | 0% | 41% | 634 | 15% | 0 | 3958 | 1121 | 0 |
| 12 | 122 | 9 | 0% | 33% | 645 | 14% | 0 | 4037 | 1142 | 0 |
| 13 | 101 | 12 | 0% | 28% | 645 | 12% | 0 | 4132 | 1178 | 0 |
| 14 | 133 | 11 | 0% | 37% | 645 | 12% | 0 | 4233 | 1199 | 0 |
| 15 | 130 | 11 | 0% | 38% | 645 | 14% | 0 | 4330 | 1231 | 0 |
| 16 | 101 | 12 | 0% | 29% | 646 | 14% | 0 | 4428 | 1232 | 3 |
| 17 | 134 | 10 | 0% | 38% | 646 | 14% | 0 | 4530 | 1251 | 22 |
| 18 | 135 | 10 | 0% | 40% | 646 | 16% | 0 | 4608 | 1258 | 44 |
| 19 | 110 | 14 | 0% | 34% | 646 | 14% | 0 | 4748 | 1183 | 177 |
| 20 | 156 | 12 | 0% | 45% | 646 | 18% | 0 | 4874 | 1086 | 372 |
| 21 | 158 | 12 | 0% | 44% | 646 | 17% | 0 | 5055 | 925 | 566 |
| 22 | 105 | 14 | 0% | 30% | 647 | 12% | 0 | 5225 | 806 | 750 |
| 23 | 161 | 11 | 0% | 44% | 646 | 14% | 0 | 5371 | 711 | 828 |
| 24 | 170 | 8 | 0% | 45% | 647 | 17% | 0 | 5467 | 578 | 981 |
| 25 | 102 | 12 | 0% | 27% | 647 | 12% | 0 | 5566 | 605 | 981 |
| 26 | 167 | 7 | 0% | 42% | 647 | 16% | 0 | 5674 | 581 | 1031 |
| 27 | 200 | 7 | 0% | 50% | 638 | 16% | 0 | 5766 | 587 | 1053 |
| 28 | 58 | 10 | 0% | 17% | 638 | 7% | 0 | 5865 | 577 | 1077 |
| 29 | 39 | 14 | 0% | 12% | 637 | 4% | 0 | 5915 | 557 | 1098 |
| 30 | 1 | 19 | 0% | 1% | 636 | 2% | 0 | 5924 | 523 | 1127 |

#### By endpoint

| endpoint | requests | p50 ms | p95 ms | p99 ms | max ms | failed |
|---|--:|--:|--:|--:|--:|--:|
| auctions | 6355 | 5 | 9 | 3505 | 9601 | 0% |
| auction | 90305 | 4 | 6 | 21 | 5704 | 0% |
| bid-history | 13442 | 4 | 6 | 10 | 588 | 0% |
| bid | 2557 | 12 | 35 | 486 | 1597 | 0% |
| book | 9233 | 13 | 39 | 488 | 10001 | 0.01% |
| event-detail | 28857 | 5 | 8 | 140 | 9440 | 0% |
| event-list | 28853 | 3 | 5 | 100 | 8957 | 0% |
| ipn | 6886 | 13 | 27 | 276 | 8974 | 0% |
| leaderboard | 31527 | 4 | 5 | 20 | 5398 | 0% |
| login | 1190 | 79 | 8566 | 10000 | 10001 | 3.28% |
| orders | 6345 | 3 | 5 | 6 | 72 | 0% |
| order | 6534 | 4 | 7 | 167 | 558 | 0% |
| pay | 6603 | 11 | 32 | 370 | 10000 | 0.02% |
| refresh | 3261 | 8 | 13 | 22 | 258 | 0% |
| return | 6541 | 4 | 6 | 154 | 602 | 0% |
| seat-map | 4599 | 7 | 23 | 317 | 8566 | 0% |
| tickets | 5918 | 3 | 10 | 105 | 605 | 0% |

Total: 259006 requests, 142 req/s on average.

#### What happened

| | count |
|---|--:|
| bookings | 7562 |
| payments ok | 5918 |
| payments declined | 616 |
| duplicate ipn answered 02 | 345 |
| abandoned | 1130 |
| seat conflicts | 956 |
| sold out | 7831 |
| not on sale | 457 |
| bids accepted | 2017 |
| bids outbid | 505 |
| bids too late | 35 |
| auction orders paid | 5 |
| auction orders ignored | 5 |
| paid but not applied | 0 |
| unexpected | 41 |

#### Data after the run

| check | found (must be 0) |
|---|--:|
| a seat with two live tickets | 0 |
| a paid order whose tickets do not match its lines | 0 |
| a ticket on an order that is not paid | 0 |
| a standing zone whose counter does not match orders and auctions | 0 |
| a sold seat without a ticket | 0 |
| a ticket on a seat that is not sold | 0 |
| a held seat without an unpaid order | 0 |
| a paid order without exactly one successful payment | 0 |
| a successful payment on an order that is not paid | 0 |
| an auction price that is not its highest bid | 0 |
| an auction whose bid count is wrong | 0 |
| a bid below the one before plus the increment | 0 |
| an auction still live 2 minutes after its end | 0 |
| an unpaid order 12 minutes past its deadline | 0 |
| a stored payment callback that was not processed | 0 |

- auction offers ACCEPTED: 5
- auction offers EXPIRED: 2
- auction offers PENDING: 5
- auctions AWAITING_PAYMENT: 5
- auctions SETTLED: 5
- bids total: 2017
- orders CANCELLED: 1173
- orders PAID: 5925
- orders PENDING_PAYMENT: 476
- payments FAILED: 616
- payments PENDING: 62
- payments SUCCEEDED: 5925

#### Health

- app: status running, restarts 0, killed for memory false
- database: status running, restarts 0, killed for memory false
- cache: status running, restarts 0, killed for memory false
- application log: 0 ERROR and 2 WARN lines
- database log: 0 deadlocks, 0 lock waits over 1 s, 0 statements over 1 s
- garbage collection: 942 pauses, 5.4 s in total, longest 41 ms

Most frequent WARN and ERROR messages:

```
      1 WARN o.s.core.events.SpringDocAppInitializer  : SpringDoc /vN/api-docs endpoint is enabled by default. To disable it in production, set the property 'springdoc.api-docs.enabled=false'
      1 WARN o.s.core.events.SpringDocAppInitializer  : SpringDoc /swagger-ui.html endpoint is enabled by default. To disable it in production, set the property 'springdoc.swagger-ui.enabled=false'
```

**Notes:** All 41 unexpected answers were 10 s timeouts between 23:34:02 and 23:34:04: 39 logins, 1 booking, 1 payment start, about 10 s after the 150 bidders logged in at the same moment.

## 2026-11-26

### Run 10: rehearsal of the concurrency test

- **When:** 00:43 to 00:46
- **Command:** `ROUNDS=1 LEVELS="5 20" LEVEL_SECONDS=20 CONFIRM=20 WARM_MIN=2 WARM_MAX=2 COOLDOWN=3 KEEP=1 loadtest/run.sh concurrency`
- **Load:** 70% reads, 30% bookings, 5 and 20 requests open at once, 20 s each

- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 5 20; 20s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

#### Round 1, started 00:43:39

Warm-up, not counted: 2 runs at 50 requests at once, req/s 125 216.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 5 | 206 | 23 | 72 | 80 | 59 | 78 | 0% | 0 | 5 | good |
| ladder | 20 | 310 | 63 | 175 | 190 | 89 | 188 | 0% | 0 | 20 | good |
| confirm | 20 | 365 | 54 | 119 | 184 | 83 | 180 | 0% | 0 | 20 | good |

Server side (CPU: 100% = one core; connections: pool of 10).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 5 |  | 193% | 58% | 8% | 26% | 58 MB |
| ladder | 20 |  | 199% | 95% | 9% | 45% | 67 MB |
| confirm | 20 |  | 198% | 92% | 9% | 54% | 65 MB |

Stock check: 10280 tickets taken from the hot zone, 10280 in orders.

**Highest rate without breaking: 365 req/s**, with 20 requests open, p95 119 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 20** (held for 20s: yes).
**Never broke; the highest level tried is in the table.**
Round 1 ended 00:46:20.

**Notes:** The server-side columns are empty because of a syntax error in the script, fixed right after; the fixed code read the saved samples correctly.

### Run 11: requests open at once, first round (second round lost to sleep)

- **When:** 00:46 to 01:13; the second round started at 01:14 and was cut off
- **Command:** `make load-test PROFILE=concurrency`
- **Load:** 70% reads (half the cached event list, half one uncached event) and 30% bookings (one standing ticket, all in
  one zone). Closed model: every client sends its next request as soon as the answer arrives, so the number of clients is
  the number of requests open. Warm-up in 60 s runs at 50 open requests until the rate changes by under 10% (3 to 6 runs).
  Levels 10 to 9,600, 10 s of arriving then 60 s measured, 15 s between levels; one level between the last good and the
  first slow one; the best good level again for 180 s. Request timeout 10 s. Orders stay payable for 120 minutes so the
  cancellation job stays idle. Thread and connection pool figures are read every few seconds from the actuator metrics,
  on a port of their own (`loadtest/compose.metrics.yaml`)
- **Host at the start:** load average 6.0 (the rehearsal had just ended), a web browser open using about 10% of one core,
  15.6 GiB free

- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 10 25 50 100 200 400 800 1600 3200 6400 9600; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

#### Round 1, started 00:47:06

Warm-up, not counted: 6 runs at 50 requests at once, req/s 196 290 327 380 512 509.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 10 | 588 | 16 | 63 | 68 | 52 | 67 | 0% | 0 | 9 | good |
| ladder | 25 | 533 | 46 | 116 | 170 | 74 | 165 | 0% | 0 | 24 | good |
| ladder | 50 | 519 | 95 | 295 | 386 | 110 | 374 | 0% | 0 | 49 | good |
| ladder | 100 | 505 | 196 | 637 | 868 | 279 | 804 | 0% | 0 | 99 | slow |
| ladder | 200 | 508 | 391 | 1309 | 1809 | 524 | 1705 | 0% | 0 | 199 | slow |
| ladder | 400 | 526 | 756 | 1665 | 2069 | 900 | 1978 | 0% | 0 | 398 | slow |
| ladder | 800 | 520 | 1530 | 2493 | 2906 | 1728 | 2802 | 0% | 0 | 796 | slow |
| ladder | 1600 | 549 | 2922 | 3904 | 4308 | 3216 | 4222 | 0% | 0 | 1605 | slow |
| ladder | 3200 | 547 | 5830 | 6986 | 7425 | 6315 | 7329 | 0% | 0 | 3191 | slow |
| ladder | 6400 | 642 | 8454 | 10001 | 10004 | 10001 | 10001 | 99.74% | 38392 | 5424 | **broken** |
| between | 75 | 503 | 148 | 475 | 607 | 190 | 584 | 0% | 0 | 74 | good |
| confirm | 75 | 514 | 145 | 479 | 605 | 194 | 585 | 0% | 0 | 74 | good |

Server side (CPU: 100% = one core; connections: pool of 10).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 10 | 10 | 10 | 0 | 5.8 | 0.0 | 5.3 | 200% | 153% | 15% | 80% | 69 MB |
| ladder | 25 | 25 | 10 | 15 | 10.7 | 8.6 | 8.7 | 185% | 141% | 12% | 80% | 75 MB |
| ladder | 50 | 50 | 10 | 39 | 11.2 | 38.4 | 9.0 | 198% | 154% | 12% | 85% | 83 MB |
| ladder | 100 | 100 | 10 | 90 | 11.6 | 103.7 | 9.0 | 197% | 153% | 12% | 87% | 101 MB |
| ladder | 200 | 200 | 10 | 190 | 11.3 | 226.7 | 8.7 | 186% | 134% | 11% | 83% | 136 MB |
| ladder | 400 | 200 | 10 | 191 | 11.2 | 225.3 | 8.8 | 178% | 136% | 12% | 89% | 200 MB |
| ladder | 800 | 200 | 10 | 191 | 11.1 | 224.4 | 9.1 | 198% | 155% | 13% | 91% | 335 MB |
| ladder | 1600 | 200 | 10 | 190 | 10.7 | 216.6 | 9.0 | 193% | 159% | 13% | 90% | 604 MB |
| ladder | 3200 | 200 | 10 | 188 | 10.6 | 215.8 | 9.0 | 200% | 150% | 13% | 91% | 1142 MB |
| ladder | 6400 | 200 | 10 | 190 | 11.4 | 231.0 | 9.1 | 196% | 138% | 13% | 111% | 2219 MB |
| between | 75 | 75 | 10 | 65 | 11.6 | 74.1 | 8.8 | 178% | 135% | 12% | 81% | 90 MB |
| confirm | 75 | 75 | 10 | 65 | 11.3 | 70.6 | 8.9 | 193% | 149% | 12% | 84% | 106 MB |

Stock check: 200516 tickets taken from the hot zone, 200516 in orders.

**Highest rate without breaking: 588 req/s**, with 10 requests open, p95 63 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 75** (held for 180s: yes).
**Broke (1% or more failed) at 6400 requests open.**
Round 1 ended 01:13:39.

#### Round 2, started 01:14:00

**What happened to round 2:** the laptop's idle daemon suspended the machine at 01:16:34, 30 minutes after the last
input. It resumed at 05:52:59. The round continued with access tokens that had expired (2 h), so it was stopped and
thrown away. `run.sh` and `soak.sh` now hold a systemd sleep inhibitor while they run.

### Run 12: requests open at once, second round

- **When:** 05:55 to 06:21 (the script calls it round 1 of this invocation)
- **Command:** `ROUNDS=1 make load-test PROFILE=concurrency`
- **Load:** as run 11, on a new stack
- **Host at the start:** load average 3.1 (falling after the stopped run), browser nearly idle, 15.7 GiB free; no suspend
  during the run

- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 10 25 50 100 200 400 800 1600 3200 6400 9600; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

#### Round 2, started 05:55:15

Warm-up, not counted: 6 runs at 50 requests at once, req/s 166 216 265 311 401 506.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 10 | 506 | 19 | 65 | 70 | 53 | 69 | 0% | 0 | 9 | good |
| ladder | 25 | 525 | 46 | 115 | 170 | 75 | 165 | 0% | 0 | 24 | good |
| ladder | 50 | 531 | 93 | 295 | 386 | 106 | 371 | 0% | 0 | 49 | good |
| ladder | 100 | 506 | 196 | 621 | 874 | 279 | 804 | 0% | 0 | 99 | slow |
| ladder | 200 | 502 | 395 | 1313 | 1793 | 527 | 1696 | 0% | 0 | 198 | slow |
| ladder | 400 | 527 | 756 | 1701 | 2124 | 925 | 2018 | 0% | 0 | 399 | slow |
| ladder | 800 | 534 | 1493 | 2477 | 2928 | 1780 | 2809 | 0% | 0 | 797 | slow |
| ladder | 1600 | 507 | 3129 | 4202 | 4675 | 3501 | 4577 | 0% | 0 | 1588 | slow |
| ladder | 3200 | 497 | 6475 | 7779 | 8276 | 7082 | 8156 | 0% | 0 | 3219 | slow |
| ladder | 6400 | 641 | 8137 | 10001 | 10004 | 10001 | 10001 | 99.79% | 38383 | 5216 | **broken** |
| between | 75 | 503 | 148 | 490 | 658 | 198 | 604 | 0% | 0 | 74 | good |
| confirm | 75 | 486 | 153 | 495 | 675 | 201 | 612 | 0% | 0 | 74 | good |

Server side (CPU: 100% = one core; connections: pool of 10).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 10 | 10 | 10 | 0 | 6.3 | 0.0 | 4.9 | 202% | 162% | 15% | 81% | 66 MB |
| ladder | 25 | 25 | 10 | 14 | 10.8 | 8.4 | 8.8 | 198% | 149% | 13% | 80% | 74 MB |
| ladder | 50 | 50 | 10 | 41 | 11.1 | 40.0 | 9.1 | 187% | 151% | 12% | 81% | 85 MB |
| ladder | 100 | 100 | 10 | 90 | 11.4 | 102.2 | 9.0 | 201% | 147% | 12% | 86% | 102 MB |
| ladder | 200 | 200 | 10 | 185 | 11.4 | 230.1 | 8.9 | 195% | 143% | 13% | 86% | 135 MB |
| ladder | 400 | 200 | 10 | 190 | 11.1 | 225.3 | 8.8 | 188% | 149% | 12% | 91% | 202 MB |
| ladder | 800 | 200 | 10 | 190 | 10.8 | 219.7 | 8.8 | 191% | 141% | 13% | 89% | 335 MB |
| ladder | 1600 | 200 | 10 | 188 | 11.4 | 232.9 | 9.0 | 201% | 132% | 11% | 80% | 607 MB |
| ladder | 3200 | 200 | 10 | 189 | 11.8 | 238.9 | 8.8 | 190% | 145% | 12% | 85% | 1137 MB |
| ladder | 6400 | 200 | 10 | 190 | 12.4 | 249.5 | 9.2 | 197% | 130% | 12% | 115% | 2206 MB |
| between | 75 | 75 | 10 | 63 | 11.5 | 71.7 | 9.0 | 200% | 159% | 12% | 86% | 91 MB |
| confirm | 75 | 75 | 10 | 66 | 11.9 | 74.2 | 9.0 | 202% | 154% | 14% | 86% | 104 MB |

Stock check: 188401 tickets taken from the hot zone, 188401 in orders.

**Highest rate without breaking: 534 req/s**, with 800 requests open, p95 2477 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 75** (held for 180s: yes).
**Broke (1% or more failed) at 6400 requests open.**
Round 2 ended 06:21:50.

**Notes on runs 11 and 12:** warm-up used all six runs both times; in run 12 the rate was still climbing at the last one
(401 to 506 req/s), so its first level may be slightly low. `req/s x avg` (Little's law) stays within 5% of the number of
open requests from 25 to 3,200 in both rounds (about 9.5 at 10), which checks the measurement. At 6,400 nearly every request timed out. k6 used
at most about 1.2 of the 7 threads left to it.

### Run 13: smoke check before committing

- **When:** 06:22, a few minutes
- **Command:** `make load-test PROFILE=smoke`
- **Load:** as run 2

| run | target | req/s | p50 ms | p95 ms | p99 ms | failed |
|---|--:|--:|--:|--:|--:|--:|
| read | 20 | 20 | 9 | 17 | 18 | 0% |
| book | 20 | 20 | 25 | 32 | 33 | 0% |
| mixed | 20 | 20 | 8 | 27 | 33 | 0% |

100 quick requests from one user: none refused. 409 tickets taken from the hot zone, 409 in orders.
