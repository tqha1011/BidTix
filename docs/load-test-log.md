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

Application code: commit `6dd5be7` for runs 1 to 13 (the commits after it add only the image build, scripts and documents);
runs 14 and later say which commit they ran.
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

### Run 14: first trial of two instances behind nginx

- **When:** 08:40 to 08:43, **on battery** (the numbers are only a check that the setup works)
- **Command:** `APP_REPLICAS=2 ROUNDS=1 LEVELS="20" LEVEL_SECONDS=20 CONFIRM=15 WARM_MIN=1 WARM_MAX=1 WARM_CLIENTS=20 loadtest/run.sh concurrency`
- **Scripts:** `loadtest/compose.scale.yaml` and `loadtest/nginx.conf` are new and, like the change to `run.sh`, were not
  yet committed during runs 14 to 18

- Commit 8525aab
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 2 x app 2 CPU / 2 GB behind nginx 1 CPU, PostgreSQL 2 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine
- Other containers running during the test: none
- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 20; 20s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

#### Round 1, started 08:41:11

Warm-up, not counted: 1 runs at 20 requests at once, req/s 128.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 20 | 333 | 59 | 195 | 290 | 84 | 269 | 0% | 0 | 20 | good |
| confirm | 20 | 533 | 37 | 102 | 161 | 66 | 148 | 0% | 0 | 20 | good |

Server side (CPU: 100% = one core; connections: pool of 10 per instance, figures added up over 2).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 20 | 27 | 16 | 2 | 22.1 | 0.5 | 10.6 | 361% | 77% | 9% | 6% | 39% | 64 MB |
| confirm | 20 | 21 | 12 | 0 | 11.2 | 0.1 | 9.3 | 388% | 105% | 13% | 9% | 46% | 67 MB |

Stock check: 6992 tickets taken from the hot zone, 6992 in orders.

Requests answered by each instance since it started: 11637 11637.

**Highest rate without breaking: 533 req/s**, with 20 requests open, p95 102 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 20** (held for 15s: yes).
**Never broke; the highest level tried is in the table.**
Round 1 ended 08:43:14.

**Notes:** both instances started together on an empty database; one applied the 19 migrations, the other found the
schema up to date. No error in any log.

### Run 15: smoke check of the single instance after the script change

- **When:** 08:43, on battery
- **Command:** `loadtest/run.sh smoke`

- Commit 8525aab
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 1 x app 2 CPU / 2 GB, PostgreSQL 2 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine; pass = p95 < 500 ms, failed < 1%, dropped < 1%
- Other containers running during the test: none

| run | target | req/s | p50 ms | p95 ms | p99 ms | failed | dropped | pass | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|
| read smoke | 20 | 20 | 11 | 63 | 89 | 0% | 0% | yes | 108% | 5% | 4% | 8% | 68 MB |
| book smoke | 20 | 20 | 29 | 97 | 150 | 0% | 0% | yes | 103% | 12% | 3% | 10% | 68 MB |
| mixed smoke | 20 | 20 | 11 | 32 | 39 | 0% | 0% | yes | 48% | 6% | 6% | 10% | 66 MB |

100 quick requests from one user: none refused.

Stock check: 364 tickets taken from the hot zone, 364 in orders.

### Run 16: one or two instances, 2 or 4 database CPUs

- **When:** 17:57 to 20:05, on mains power. CPU settings: energy preference `balance_performance`, governor `powersave`
  (read at the start); firmware profile (`platform_profile`) `balanced-performance`, which the `lenovo-wmi-gamezone` driver uses for Lenovo's
  Performance mode, and power-profiles-daemon `balanced` (both read after the run, at 20:10; the daemon logs that it does
  not know the firmware value)
- **Command:** eight runs of `ROUNDS=1 LEVELS="50 100 150 200 400" LEVEL_SECONDS=60 CONFIRM=60 loadtest/run.sh concurrency`,
  with `APP_REPLICAS` (unset: no nginx), `DB_CPUS` and `WARM_CLIENTS` (50 per instance) per configuration. Four
  configurations, two passes, the second in reverse order
- **Load:** as run 11, apart from the levels

| order | run | started | ended |
|--:|---|---|---|
| 1 | direct-db2, pass 1 | 17:57:47 | 18:16:07 |
| 2 | nginx1-db2, pass 1 | 18:16:07 | 18:27:19 |
| 3 | nginx2-db2, pass 1 | 18:27:19 | 18:43:47 |
| 4 | nginx2-db4, pass 1 | 18:43:47 | 19:00:15 |
| 5 | nginx2-db4, pass 2 | 19:00:15 | 19:16:40 |
| 6 | nginx2-db2, pass 2 | 19:16:40 | 19:34:15 |
| 7 | nginx1-db2, pass 2 | 19:34:15 | 19:51:36 |
| 8 | direct-db2, pass 2 | 19:51:36 | 20:05:38 |

**CPU clock during each run** (sampled every 15 s, cooldowns included: the mean over the 12 threads of
`scaling_cur_freq`, and the hottest thermal zone). It was **not** the same from one configuration to the next.

| run | mean MHz | samples under 1.2 GHz | samples over 2.5 GHz | mean °C | highest °C |
|---|--:|--:|--:|--:|--:|
| direct-db2, pass 1 | 1350 | 64% | 11% | 48 | 63 |
| nginx1-db2, pass 1 | 1537 | 55% | 18% | 49 | 64 |
| nginx2-db2, pass 1 | 1480 | 56% | 14% | 51 | 70 |
| nginx2-db4, pass 1 | 2181 | 36% | 47% | 65 | 86 |
| nginx2-db4, pass 2 | 2320 | 29% | 55% | 67 | 88 |
| nginx2-db2, pass 2 | 1605 | 57% | 20% | 55 | 74 |
| nginx1-db2, pass 2 | 1250 | 68% | 10% | 49 | 84 |
| direct-db2, pass 2 | 1211 | 77% | 7% | 48 | 63 |

#### direct-db2, pass 1

- Commit bff74cf
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 1 x app 2 CPU / 2 GB, PostgreSQL 2 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine
- Other containers running during the test: none
- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 50 100 150 200 400; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

##### Round 1, started 17:59:13

Warm-up, not counted: 6 runs at 50 requests at once, req/s 113 180 211 291 371 542.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 50 | 534 | 93 | 293 | 371 | 104 | 348 | 0% | 0 | 49 | good |
| ladder | 100 | 557 | 178 | 603 | 804 | 256 | 769 | 0% | 0 | 99 | slow |
| ladder | 150 | 551 | 270 | 961 | 1301 | 396 | 1206 | 0% | 0 | 149 | slow |
| ladder | 200 | 731 | 273 | 1009 | 1466 | 388 | 1335 | 0% | 0 | 199 | slow |
| ladder | 400 | 533 | 744 | 1696 | 2195 | 925 | 2081 | 0% | 0 | 397 | slow |
| between | 75 | 612 | 121 | 411 | 583 | 185 | 532 | 0% | 0 | 74 | good |
| confirm | 75 | 589 | 126 | 414 | 572 | 184 | 517 | 0% | 0 | 74 | good |

Server side (CPU: 100% = one core; connections: pool of 10 per instance, figures added up over 1).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 50 | 50 | 10 | 41 | 11.1 | 41.0 | 9.0 | 179% | 147% | 13% | - | 81% | 82 MB |
| ladder | 100 | 100 | 10 | 90 | 10.5 | 94.3 | 8.9 | 187% | 148% | 12% | - | 84% | 103 MB |
| ladder | 150 | 150 | 10 | 139 | 10.5 | 152.5 | 9.0 | 201% | 146% | 12% | - | 85% | 117 MB |
| ladder | 200 | 200 | 10 | 189 | 8.1 | 164.1 | 9.0 | 194% | 146% | 13% | - | 84% | 139 MB |
| ladder | 400 | 200 | 10 | 190 | 10.9 | 221.1 | 9.0 | 199% | 150% | 12% | - | 91% | 202 MB |
| between | 75 | 75 | 10 | 64 | 9.5 | 59.6 | 9.0 | 199% | 155% | 13% | - | 85% | 91 MB |
| confirm | 75 | 75 | 10 | 65 | 9.8 | 62.3 | 9.0 | 199% | 151% | 12% | - | 85% | 93 MB |

Stock check: 118908 tickets taken from the hot zone, 118908 in orders.

Requests answered by each instance since it started: 397669.

**Highest rate without breaking: 731 req/s**, with 200 requests open, p95 1009 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 75** (held for 60s: yes).
**Never broke; the highest level tried is in the table.**
Round 1 ended 18:16:06.

#### nginx1-db2, pass 1

- Commit bff74cf
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 1 x app 2 CPU / 2 GB behind nginx 1 CPU, PostgreSQL 2 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine
- Other containers running during the test: none
- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 50 100 150 200 400; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

##### Round 1, started 18:16:33

Warm-up, not counted: 3 runs at 50 requests at once, req/s 168 230 234.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 50 | 277 | 178 | 505 | 688 | 207 | 618 | 0% | 0 | 49 | slow |
| ladder | 100 | 346 | 286 | 919 | 1277 | 396 | 1193 | 0% | 0 | 99 | slow |
| ladder | 150 | 525 | 283 | 982 | 1338 | 399 | 1264 | 0% | 0 | 149 | slow |
| ladder | 200 | 474 | 419 | 1405 | 1905 | 579 | 1803 | 0% | 0 | 198 | slow |
| ladder | 400 | 548 | 726 | 1691 | 2160 | 969 | 2055 | 0% | 0 | 398 | slow |

Server side (CPU: 100% = one core; connections: pool of 10 per instance, figures added up over 1).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 50 | 50 | 10 | 36 | 21.5 | 64.0 | 9.2 | 198% | 91% | 8% | 7% | 50% | 81 MB |
| ladder | 100 | 99 | 10 | 87 | 17.0 | 146.4 | 9.0 | 187% | 99% | 9% | 8% | 62% | 98 MB |
| ladder | 150 | 150 | 10 | 140 | 11.2 | 162.0 | 9.0 | 183% | 136% | 12% | 10% | 80% | 117 MB |
| ladder | 200 | 200 | 10 | 183 | 12.1 | 239.5 | 8.9 | 189% | 140% | 12% | 10% | 86% | 133 MB |
| ladder | 400 | 200 | 10 | 189 | 10.6 | 215.6 | 8.8 | 188% | 147% | 12% | 10% | 87% | 203 MB |

Stock check: 57128 tickets taken from the hot zone, 57128 in orders.

Requests answered by each instance since it started: 191581.

**Highest rate without breaking: 548 req/s**, with 400 requests open, p95 1691 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 0** (held for 60s: no).
**Never broke; the highest level tried is in the table.**
Round 1 ended 18:27:18.

#### nginx2-db2, pass 1

- Commit bff74cf
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 2 x app 2 CPU / 2 GB behind nginx 1 CPU, PostgreSQL 2 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine
- Other containers running during the test: none
- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 50 100 150 200 400; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

##### Round 1, started 18:27:47

Warm-up, not counted: 5 runs at 100 requests at once, req/s 328 429 568 491 466.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 50 | 451 | 110 | 459 | 653 | 114 | 596 | 0% | 0 | 50 | good |
| ladder | 100 | 512 | 195 | 801 | 1182 | 266 | 1093 | 0% | 0 | 100 | slow |
| ladder | 150 | 502 | 297 | 1357 | 1907 | 428 | 1796 | 0% | 0 | 149 | slow |
| ladder | 200 | 444 | 448 | 1594 | 2126 | 553 | 2006 | 0% | 0 | 199 | slow |
| ladder | 400 | 505 | 782 | 2535 | 3284 | 1066 | 3149 | 0% | 0 | 395 | slow |
| between | 75 | 473 | 157 | 659 | 960 | 192 | 889 | 0% | 0 | 74 | slow |
| confirm | 50 | 595 | 83 | 385 | 619 | 102 | 571 | 0% | 0 | 50 | good |

Server side (CPU: 100% = one core; connections: pool of 10 per instance, figures added up over 2).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 50 | 54 | 20 | 39 | 27.1 | 39.8 | 19.2 | 178% | 198% | 13% | 9% | 68% | 80 MB |
| ladder | 100 | 103 | 20 | 83 | 24.5 | 97.9 | 18.8 | 183% | 187% | 13% | 10% | 79% | 99 MB |
| ladder | 150 | 156 | 20 | 131 | 24.9 | 162.8 | 18.6 | 178% | 190% | 13% | 9% | 76% | 117 MB |
| ladder | 200 | 206 | 20 | 186 | 28.3 | 255.5 | 19.6 | 176% | 203% | 12% | 9% | 72% | 133 MB |
| ladder | 400 | 400 | 20 | 371 | 24.9 | 404.0 | 19.5 | 190% | 204% | 13% | 10% | 77% | 199 MB |
| between | 75 | 76 | 20 | 60 | 26.4 | 71.9 | 19.4 | 179% | 187% | 13% | 9% | 66% | 88 MB |
| confirm | 50 | 53 | 20 | 32 | 20.9 | 30.9 | 18.5 | 172% | 168% | 13% | 10% | 64% | 86 MB |

Stock check: 120399 tickets taken from the hot zone, 120399 in orders.

Requests answered by each instance since it started: 200220 200218.

**Highest rate without breaking: 595 req/s**, with 50 requests open, p95 385 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 50** (held for 60s: yes).
**Never broke; the highest level tried is in the table.**
Round 1 ended 18:43:45.

#### nginx2-db4, pass 1

- Commit bff74cf
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 2 x app 2 CPU / 2 GB behind nginx 1 CPU, PostgreSQL 4 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine
- Other containers running during the test: none
- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 50 100 150 200 400; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

##### Round 1, started 18:44:15

Warm-up, not counted: 5 runs at 100 requests at once, req/s 245 334 541 1192 1170.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 50 | 1327 | 37 | 141 | 247 | 32 | 203 | 0% | 0 | 50 | good |
| ladder | 100 | 1338 | 74 | 284 | 476 | 77 | 401 | 0% | 0 | 99 | good |
| ladder | 150 | 1114 | 135 | 475 | 1340 | 143 | 1066 | 0% | 0 | 150 | good |
| ladder | 200 | 1400 | 144 | 575 | 845 | 164 | 760 | 0% | 0 | 201 | slow |
| ladder | 400 | 1049 | 380 | 1169 | 2953 | 525 | 2325 | 0% | 0 | 399 | slow |
| between | 175 | 1054 | 165 | 656 | 1482 | 220 | 1246 | 0% | 0 | 174 | slow |
| confirm | 150 | 1332 | 112 | 454 | 608 | 128 | 572 | 0% | 0 | 149 | good |

Server side (CPU: 100% = one core; connections: pool of 10 per instance, figures added up over 2).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 50 | 58 | 20 | 36 | 9.0 | 13.5 | 18.1 | 241% | 247% | 14% | 13% | 78% | 95 MB |
| ladder | 100 | 106 | 20 | 86 | 9.2 | 37.8 | 18.7 | 247% | 274% | 16% | 13% | 85% | 113 MB |
| ladder | 150 | 159 | 20 | 136 | 11.3 | 75.1 | 18.8 | 220% | 248% | 14% | 11% | 76% | 125 MB |
| ladder | 200 | 208 | 20 | 192 | 8.8 | 82.4 | 18.6 | 258% | 270% | 16% | 13% | 90% | 145 MB |
| ladder | 400 | 387 | 20 | 364 | 12.0 | 191.2 | 18.6 | 220% | 229% | 14% | 11% | 79% | 213 MB |
| between | 175 | 184 | 20 | 159 | 11.8 | 94.0 | 18.7 | 235% | 249% | 15% | 12% | 79% | 136 MB |
| confirm | 150 | 155 | 20 | 135 | 9.2 | 61.8 | 18.4 | 251% | 285% | 16% | 12% | 83% | 131 MB |

Stock check: 253931 tickets taken from the hot zone, 253931 in orders.

Requests answered by each instance since it started: 423631 423630.

**Highest rate without breaking: 1400 req/s**, with 200 requests open, p95 575 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 150** (held for 60s: yes).
**Never broke; the highest level tried is in the table.**
Round 1 ended 19:00:13.

#### nginx2-db4, pass 2

- Commit bff74cf
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 2 x app 2 CPU / 2 GB behind nginx 1 CPU, PostgreSQL 4 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine
- Other containers running during the test: none
- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 50 100 150 200 400; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

##### Round 1, started 19:00:43

Warm-up, not counted: 5 runs at 100 requests at once, req/s 308 509 1525 1034 1122.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 50 | 1115 | 44 | 164 | 491 | 37 | 371 | 0% | 0 | 50 | good |
| ladder | 100 | 1604 | 62 | 256 | 346 | 68 | 325 | 0% | 0 | 99 | good |
| ladder | 150 | 1229 | 122 | 453 | 1094 | 130 | 774 | 0% | 0 | 149 | good |
| ladder | 200 | 1305 | 153 | 498 | 949 | 153 | 715 | 0% | 0 | 199 | good |
| ladder | 400 | 1317 | 302 | 911 | 1880 | 353 | 1174 | 0% | 0 | 398 | slow |
| between | 300 | 1034 | 284 | 949 | 2487 | 364 | 1821 | 0% | 0 | 293 | slow |
| confirm | 200 | 1262 | 158 | 672 | 971 | 186 | 851 | 0% | 0 | 199 | slow |

Server side (CPU: 100% = one core; connections: pool of 10 per instance, figures added up over 2).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 50 | 54 | 20 | 34 | 10.8 | 16.5 | 18.1 | 205% | 239% | 14% | 11% | 71% | 90 MB |
| ladder | 100 | 112 | 20 | 84 | 7.5 | 31.2 | 18.2 | 271% | 289% | 17% | 13% | 95% | 121 MB |
| ladder | 150 | 160 | 20 | 132 | 9.7 | 65.5 | 18.6 | 229% | 248% | 15% | 12% | 79% | 127 MB |
| ladder | 200 | 208 | 20 | 184 | 9.4 | 87.6 | 18.9 | 252% | 272% | 15% | 13% | 84% | 150 MB |
| ladder | 400 | 390 | 20 | 377 | 9.2 | 157.5 | 18.5 | 250% | 262% | 15% | 12% | 90% | 215 MB |
| between | 300 | 308 | 20 | 285 | 11.5 | 149.8 | 18.7 | 196% | 253% | 13% | 9% | 76% | 179 MB |
| confirm | 200 | 210 | 20 | 184 | 9.6 | 91.2 | 18.3 | 234% | 275% | 14% | 12% | 83% | 144 MB |

Stock check: 276777 tickets taken from the hot zone, 276777 in orders.

Requests answered by each instance since it started: 460976 460975.

**Highest rate without breaking: 1604 req/s**, with 100 requests open, p95 256 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 200** (held for 60s: no).
**Never broke; the highest level tried is in the table.**
Round 1 ended 19:16:39.

#### nginx2-db2, pass 2

- Commit bff74cf
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 2 x app 2 CPU / 2 GB behind nginx 1 CPU, PostgreSQL 2 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine
- Other containers running during the test: none
- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 50 100 150 200 400; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

##### Round 1, started 19:17:09

Warm-up, not counted: 6 runs at 100 requests at once, req/s 210 343 461 616 475 491.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 50 | 484 | 102 | 423 | 633 | 112 | 592 | 0% | 0 | 49 | good |
| ladder | 100 | 577 | 172 | 713 | 1112 | 234 | 1021 | 0% | 0 | 99 | slow |
| ladder | 150 | 582 | 257 | 1088 | 1608 | 373 | 1489 | 0% | 0 | 150 | slow |
| ladder | 200 | 599 | 333 | 1491 | 2304 | 502 | 2170 | 0% | 0 | 199 | slow |
| ladder | 400 | 518 | 766 | 2632 | 3477 | 1255 | 3313 | 0% | 0 | 397 | slow |
| between | 75 | 536 | 140 | 594 | 970 | 191 | 884 | 0% | 0 | 75 | slow |
| confirm | 50 | 486 | 102 | 419 | 630 | 109 | 592 | 0% | 0 | 49 | good |

Server side (CPU: 100% = one core; connections: pool of 10 per instance, figures added up over 2).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 50 | 55 | 20 | 32 | 25.6 | 37.2 | 19.3 | 178% | 202% | 12% | 10% | 66% | 81 MB |
| ladder | 100 | 107 | 20 | 83 | 21.9 | 87.5 | 18.7 | 191% | 192% | 14% | 9% | 77% | 100 MB |
| ladder | 150 | 157 | 20 | 136 | 21.8 | 143.3 | 19.3 | 201% | 204% | 13% | 10% | 79% | 116 MB |
| ladder | 200 | 203 | 20 | 185 | 20.8 | 192.5 | 18.4 | 184% | 180% | 13% | 9% | 77% | 134 MB |
| ladder | 400 | 348 | 20 | 331 | 24.1 | 320.0 | 18.7 | 168% | 182% | 12% | 8% | 69% | 200 MB |
| between | 75 | 82 | 20 | 58 | 23.7 | 65.2 | 19.2 | 197% | 196% | 13% | 9% | 73% | 92 MB |
| confirm | 50 | 53 | 20 | 40 | 25.6 | 37.0 | 19.3 | 180% | 203% | 13% | 9% | 66% | 81 MB |

Stock check: 132521 tickets taken from the hot zone, 132521 in orders.

Requests answered by each instance since it started: 221004 221004.

**Highest rate without breaking: 599 req/s**, with 200 requests open, p95 1491 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 50** (held for 60s: yes).
**Never broke; the highest level tried is in the table.**
Round 1 ended 19:34:13.

#### nginx1-db2, pass 2

- Commit bff74cf
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 1 x app 2 CPU / 2 GB behind nginx 1 CPU, PostgreSQL 2 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine
- Other containers running during the test: none
- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 50 100 150 200 400; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

##### Round 1, started 19:34:39

Warm-up, not counted: 6 runs at 50 requests at once, req/s 159 238 276 333 395 560.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 50 | 614 | 80 | 269 | 356 | 101 | 323 | 0% | 0 | 49 | good |
| ladder | 100 | 484 | 205 | 681 | 898 | 288 | 859 | 0% | 0 | 99 | slow |
| ladder | 150 | 494 | 302 | 1023 | 1411 | 413 | 1325 | 0% | 0 | 149 | slow |
| ladder | 200 | 469 | 424 | 1397 | 1908 | 581 | 1802 | 0% | 0 | 199 | slow |
| ladder | 400 | 470 | 847 | 1881 | 2390 | 1009 | 2288 | 0% | 0 | 398 | slow |
| between | 75 | 472 | 157 | 495 | 637 | 200 | 603 | 0% | 0 | 74 | good |
| confirm | 75 | 507 | 146 | 478 | 606 | 194 | 586 | 0% | 0 | 74 | good |

Server side (CPU: 100% = one core; connections: pool of 10 per instance, figures added up over 1).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 50 | 50 | 10 | 40 | 9.6 | 34.4 | 8.9 | 179% | 144% | 10% | 10% | 78% | 84 MB |
| ladder | 100 | 100 | 10 | 90 | 12.1 | 106.4 | 9.1 | 198% | 154% | 13% | 11% | 88% | 100 MB |
| ladder | 150 | 150 | 10 | 137 | 11.8 | 169.1 | 8.9 | 192% | 144% | 11% | 10% | 85% | 114 MB |
| ladder | 200 | 200 | 10 | 190 | 12.7 | 251.2 | 9.2 | 192% | 154% | 13% | 10% | 88% | 132 MB |
| ladder | 400 | 200 | 10 | 190 | 12.4 | 248.8 | 9.1 | 194% | 150% | 14% | 11% | 91% | 199 MB |
| between | 75 | 75 | 10 | 65 | 12.4 | 76.6 | 8.8 | 188% | 146% | 12% | 10% | 86% | 91 MB |
| confirm | 75 | 75 | 10 | 65 | 11.4 | 70.2 | 9.0 | 198% | 151% | 13% | 11% | 86% | 92 MB |

Stock check: 111724 tickets taken from the hot zone, 111724 in orders.

Requests answered by each instance since it started: 371672.

**Highest rate without breaking: 614 req/s**, with 50 requests open, p95 269 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 75** (held for 60s: yes).
**Never broke; the highest level tried is in the table.**
Round 1 ended 19:51:34.

#### direct-db2, pass 2

- Commit bff74cf
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 1 x app 2 CPU / 2 GB, PostgreSQL 2 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine
- Other containers running during the test: none
- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 50 100 150 200 400; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

##### Round 1, started 19:52:00

Warm-up, not counted: 3 runs at 50 requests at once, req/s 207 283 311.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 50 | 375 | 132 | 397 | 506 | 183 | 494 | 0% | 0 | 49 | good |
| ladder | 100 | 502 | 197 | 636 | 883 | 280 | 807 | 0% | 0 | 99 | slow |
| ladder | 150 | 523 | 285 | 926 | 1284 | 390 | 1184 | 0% | 0 | 149 | slow |
| ladder | 200 | 551 | 362 | 1240 | 1696 | 506 | 1586 | 0% | 0 | 199 | slow |
| ladder | 400 | 560 | 712 | 1694 | 2184 | 931 | 2063 | 0% | 0 | 399 | slow |
| between | 75 | 521 | 142 | 466 | 597 | 190 | 574 | 0% | 0 | 74 | good |
| confirm | 75 | 533 | 139 | 458 | 591 | 191 | 568 | 0% | 0 | 74 | good |

Server side (CPU: 100% = one core; connections: pool of 10 per instance, figures added up over 1).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 50 | 50 | 10 | 39 | 15.5 | 50.8 | 9.1 | 200% | 109% | 9% | - | 59% | 81 MB |
| ladder | 100 | 100 | 10 | 89 | 11.3 | 101.1 | 8.8 | 191% | 144% | 12% | - | 83% | 99 MB |
| ladder | 150 | 150 | 10 | 140 | 11.1 | 160.6 | 8.8 | 184% | 141% | 12% | - | 85% | 117 MB |
| ladder | 200 | 200 | 10 | 190 | 10.5 | 209.7 | 8.9 | 185% | 145% | 11% | - | 84% | 137 MB |
| ladder | 400 | 200 | 10 | 189 | 10.5 | 211.5 | 9.1 | 201% | 150% | 12% | - | 88% | 201 MB |
| between | 75 | 75 | 10 | 65 | 11.2 | 70.8 | 9.0 | 196% | 147% | 12% | - | 84% | 92 MB |
| confirm | 75 | 75 | 10 | 65 | 10.9 | 67.7 | 8.9 | 198% | 151% | 13% | - | 85% | 92 MB |

Stock check: 91682 tickets taken from the hot zone, 91682 in orders.

Requests answered by each instance since it started: 304168.

**Highest rate without breaking: 560 req/s**, with 400 requests open, p95 1694 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 75** (held for 60s: yes).
**Never broke; the highest level tried is in the table.**
Round 1 ended 20:05:37.

**Notes on run 16**

- No request failed and the stock check matched after all eight runs; with two instances each answered the same number of
  requests (within 2). No `ERROR` line or deadlock in the application, database or nginx logs.
- With two instances, 8 to 46 updates of the hot zone's counter per run took over 1 s (longest about 1.7 s); with one
  instance none did.
- Warm-up stopped too early in `nginx1-db2, pass 1` (3 runs, last rate 234 req/s) and in `direct-db2, pass 2` (3 runs,
  311 req/s): their first levels are low. In `direct-db2, pass 1` and `nginx1-db2, pass 2` the rate was still climbing at
  the sixth and last warm-up run.

### Run 17: one instance, a larger database, then a larger application

- **When:** 20:18 to 20:51, on mains power; CPU settings as in run 16, the same at the start and the end
- **Command:** `APP_CPUS=2` then `4`, each with `DB_CPUS=4 ROUNDS=1 LEVELS="50 100 150 200" LEVEL_SECONDS=60 CONFIRM=60
  WARM_MIN=5 WARM_MAX=7 loadtest/run.sh concurrency` (no `APP_REPLICAS`, so one instance and no nginx)
- **Load:** as run 11, apart from the levels. One pass of each

**CPU clock** (sampled as in run 16):

| run | mean MHz | samples under 1.2 GHz | samples over 2.5 GHz | mean °C | highest °C |
|---|--:|--:|--:|--:|--:|
| app2-db4 | 1387 | 74% | 18% | 48 | 68 |
| app4-db4 | 2188 | 34% | 44% | 63 | 78 |

#### app2-db4

- Commit bff74cf
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 1 x app 2 CPU / 2 GB, PostgreSQL 4 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine
- Other containers running during the test: none
- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 50 100 150 200; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

##### Round 1, started 20:19:05

Warm-up, not counted: 7 runs at 50 requests at once, req/s 182 216 262 344 436 600 553.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 50 | 547 | 90 | 286 | 375 | 106 | 341 | 0% | 0 | 49 | good |
| ladder | 100 | 557 | 178 | 597 | 793 | 227 | 726 | 0% | 0 | 99 | slow |
| ladder | 150 | 540 | 276 | 923 | 1276 | 392 | 1183 | 0% | 0 | 149 | slow |
| ladder | 200 | 537 | 369 | 1262 | 1672 | 500 | 1538 | 0% | 0 | 198 | slow |
| between | 75 | 547 | 136 | 430 | 570 | 183 | 523 | 0% | 0 | 74 | good |
| confirm | 75 | 545 | 136 | 432 | 595 | 190 | 569 | 0% | 0 | 74 | good |

Server side (CPU: 100% = one core; connections: pool of 10 per instance, figures added up over 1).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 50 | 50 | 10 | 40 | 10.9 | 37.7 | 9.1 | 198% | 147% | 12% | - | 84% | 83 MB |
| ladder | 100 | 100 | 10 | 89 | 10.5 | 94.6 | 9.1 | 194% | 144% | 13% | - | 83% | 101 MB |
| ladder | 150 | 150 | 10 | 138 | 10.6 | 155.6 | 8.9 | 200% | 145% | 13% | - | 86% | 116 MB |
| ladder | 200 | 200 | 10 | 188 | 10.8 | 216.8 | 9.0 | 196% | 145% | 13% | - | 88% | 139 MB |
| between | 75 | 75 | 10 | 65 | 10.7 | 68.6 | 8.8 | 182% | 139% | 11% | - | 82% | 92 MB |
| confirm | 75 | 75 | 10 | 64 | 10.5 | 65.8 | 8.8 | 189% | 141% | 11% | - | 84% | 92 MB |

Stock check: 119342 tickets taken from the hot zone, 119342 in orders.

Requests answered by each instance since it started: 396827.

**Highest rate without breaking: 557 req/s**, with 100 requests open, p95 597 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 75** (held for 60s: yes).
**Never broke; the highest level tried is in the table.**
Round 1 ended 20:35:39.

#### app4-db4

- Commit bff74cf
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 1 x app 4 CPU / 2 GB, PostgreSQL 4 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine
- Other containers running during the test: none
- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 50 100 150 200; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

##### Round 1, started 20:36:03

Warm-up, not counted: 7 runs at 50 requests at once, req/s 224 525 1768 1318 1803 1614 1808.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 50 | 1492 | 33 | 103 | 269 | 39 | 239 | 0% | 0 | 49 | good |
| ladder | 100 | 1433 | 69 | 246 | 509 | 88 | 475 | 0% | 0 | 99 | good |
| ladder | 150 | 1802 | 83 | 245 | 584 | 87 | 476 | 0% | 0 | 149 | good |
| ladder | 200 | 1444 | 138 | 429 | 1126 | 193 | 1015 | 0% | 0 | 199 | good |
| confirm | 200 | 1503 | 134 | 418 | 1093 | 167 | 945 | 0% | 0 | 201 | good |

Server side (CPU: 100% = one core; connections: pool of 10 per instance, figures added up over 1).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 50 | 50 | 10 | 40 | 3.9 | 16.3 | 8.8 | 252% | 202% | 17% | - | 103% | 96 MB |
| ladder | 100 | 100 | 10 | 90 | 4.1 | 40.0 | 8.7 | 238% | 204% | 15% | - | 105% | 113 MB |
| ladder | 150 | 150 | 10 | 140 | 3.1 | 49.1 | 8.5 | 274% | 212% | 17% | - | 114% | 135 MB |
| ladder | 200 | 200 | 10 | 190 | 4.1 | 85.0 | 8.7 | 237% | 186% | 17% | - | 104% | 150 MB |
| confirm | 200 | 200 | 10 | 190 | 3.9 | 81.7 | 8.6 | 244% | 194% | 16% | - | 107% | 158 MB |

Stock check: 344668 tickets taken from the hot zone, 344668 in orders.

Requests answered by each instance since it started: 1151841.

**Highest rate without breaking: 1802 req/s**, with 150 requests open, p95 245 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 200** (held for 60s: yes).
**Never broke; the highest level tried is in the table.**
Round 1 ended 20:51:11.

**Notes on run 17**

- No request failed and the stock check matched after both runs.
- A database on 4 CPUs changed nothing for an application on 2 (about 545 req/s and 75 open requests, as in run 16 with
  2 database CPUs). The application was at its 2 CPU limit.
- With 4 application CPUs no tested level failed the mark, so the limit was not found (200 open requests was the highest).
  The mark is on all requests together: bookings alone had a p95 under 500 ms at 50, 100 and 150 open, but 945 to
  1,015 ms at 200.
- With 4 CPUs each, neither container reached its limit (application about 240-270%, database about 190-210% of 400%),
  while about 9 of the 10 pool connections were busy. The pool is the suspect; another pool size was not measured.
- The clock differed between the two runs, as in run 16, so the ratio between them is not the effect of the CPUs alone.

### Run 18: the 4 CPU instance twice more

- **When:** 21:44 to 22:15, on mains power; CPU settings as in run 16, the same at the start and the end
- **Command:** as the `app4-db4` run of run 17, two passes
- **Host:** a web browser was closed during the second warm-up run of the first pass; nothing was measured before that

**CPU clock** (sampled as in run 16):

| run | mean MHz | samples under 1.2 GHz | samples over 2.5 GHz | mean °C | highest °C |
|---|--:|--:|--:|--:|--:|
| app4-db4, pass 2 | 2389 | 30% | 54% | 64 | 82 |
| app4-db4, pass 3 | 2340 | 29% | 53% | 57 | 75 |

#### app4-db4, pass 2

- Commit bff74cf
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 1 x app 4 CPU / 2 GB, PostgreSQL 4 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine
- Other containers running during the test: none
- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 50 100 150 200; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

##### Round 1, started 21:45:06

Warm-up, not counted: 5 runs at 50 requests at once, req/s 235 1302 1321 1809 1758.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 50 | 1787 | 28 | 79 | 206 | 25 | 128 | 0% | 0 | 49 | good |
| ladder | 100 | 1312 | 76 | 252 | 652 | 90 | 588 | 0% | 0 | 99 | good |
| ladder | 150 | 1452 | 103 | 331 | 817 | 133 | 753 | 0% | 0 | 149 | good |
| ladder | 200 | 1751 | 114 | 337 | 812 | 119 | 615 | 0% | 0 | 199 | good |
| confirm | 200 | 1768 | 113 | 336 | 805 | 117 | 621 | 0% | 0 | 199 | good |

Server side (CPU: 100% = one core; connections: pool of 10 per instance, figures added up over 1).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 50 | 50 | 10 | 40 | 3.1 | 13.3 | 8.6 | 279% | 218% | 18% | - | 107% | 104 MB |
| ladder | 100 | 100 | 10 | 90 | 4.5 | 44.0 | 8.8 | 239% | 189% | 15% | - | 99% | 115 MB |
| ladder | 150 | 150 | 10 | 140 | 4.0 | 61.8 | 8.9 | 251% | 200% | 17% | - | 106% | 133 MB |
| ladder | 200 | 200 | 10 | 190 | 3.2 | 68.9 | 8.6 | 277% | 212% | 18% | - | 114% | 160 MB |
| confirm | 200 | 200 | 10 | 190 | 3.2 | 68.3 | 8.6 | 276% | 220% | 19% | - | 115% | 160 MB |

Stock check: 297571 tickets taken from the hot zone, 297571 in orders.

Requests answered by each instance since it started: 992069.

**Highest rate without breaking: 1787 req/s**, with 50 requests open, p95 79 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 200** (held for 60s: yes).
**Never broke; the highest level tried is in the table.**
Round 1 ended 21:57:58.

#### app4-db4, pass 3

- Commit bff74cf
- 12th Gen Intel(R) Core(TM) i5-12450HX, 12 threads; 1 x app 4 CPU / 2 GB, PostgreSQL 4 CPU / 1 GB, Redis 1 CPU; pool 10
- k6 v2.0.0 on the same machine
- Other containers running during the test: none
- Traffic: 70% reads, 30% bookings; every client sends its next request when the answer arrives; levels: 50 100 150 200; 60s each after 10s of arriving
- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)

##### Round 1, started 21:58:23

Warm-up, not counted: 7 runs at 50 requests at once, req/s 211 428 1683 1787 1495 1798 1392.

| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| ladder | 50 | 1417 | 35 | 111 | 316 | 39 | 273 | 0% | 0 | 49 | good |
| ladder | 100 | 1758 | 57 | 165 | 425 | 58 | 381 | 0% | 0 | 99 | good |
| ladder | 150 | 1452 | 103 | 343 | 767 | 140 | 711 | 0% | 0 | 149 | good |
| ladder | 200 | 1405 | 142 | 512 | 1004 | 196 | 939 | 0% | 0 | 199 | slow |
| between | 175 | 1792 | 97 | 291 | 690 | 100 | 542 | 0% | 0 | 174 | good |
| confirm | 175 | 1765 | 99 | 290 | 711 | 102 | 565 | 0% | 0 | 174 | good |

Server side (CPU: 100% = one core; connections: pool of 10 per instance, figures added up over 1).

| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ladder | 50 | 50 | 10 | 40 | 4.2 | 17.4 | 8.7 | 228% | 195% | 15% | - | 97% | 101 MB |
| ladder | 100 | 100 | 10 | 90 | 3.2 | 32.3 | 8.7 | 271% | 219% | 18% | - | 111% | 125 MB |
| ladder | 150 | 150 | 10 | 140 | 4.0 | 61.8 | 8.7 | 236% | 197% | 16% | - | 105% | 137 MB |
| ladder | 200 | 200 | 10 | 190 | 4.1 | 86.8 | 8.8 | 253% | 185% | 17% | - | 107% | 156 MB |
| between | 175 | 175 | 10 | 165 | 3.1 | 58.3 | 8.6 | 281% | 213% | 18% | - | 115% | 151 MB |
| confirm | 175 | 175 | 10 | 165 | 3.2 | 59.5 | 8.6 | 288% | 217% | 18% | - | 112% | 151 MB |

Stock check: 379357 tickets taken from the hot zone, 379357 in orders.

Requests answered by each instance since it started: 1266762.

**Highest rate without breaking: 1792 req/s**, with 175 requests open, p95 291 ms.
**Most requests open at once with p95 < 500 ms and < 1% failed: 175** (held for 60s: yes).
**Never broke; the highest level tried is in the table.**
Round 1 ended 22:14:57.

**The three passes together** (`app4-db4` of run 17 is pass 1)

| open requests | req/s, pass 1 / 2 / 3 | p95 ms, all requests | p95 ms, bookings only |
|--:|--:|--:|--:|
| 50 | 1492 / 1787 / 1417 | 103 / 79 / 111 | 239 / 128 / 273 |
| 100 | 1433 / 1312 / 1758 | 246 / 252 / 165 | 475 / 588 / 381 |
| 150 | 1802 / 1452 / 1452 | 245 / 331 / 343 | 476 / 753 / 711 |
| 200 | 1444 / 1751 / 1405 | 429 / 337 / 512 | 1015 / 615 / 939 |

- At 50 to 150 open requests every measurement of all three passes gave at least 1,312 req/s and a p95 (all requests) of
  343 ms or less. No request failed in any measured level.
- At 200 open requests one pass of three missed the mark (p95 512 ms).
- The p95 of bookings alone is not stable from 100 open requests up.
- The same level differs by up to about 35% between passes. The cause was not isolated.
- Across the three passes, warm-up included, 1,021,596 bookings were taken from the hot zone and the stock matched the
  orders after each. No `ERROR` line or deadlock in the logs.
