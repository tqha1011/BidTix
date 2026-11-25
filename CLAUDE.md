# CLAUDE.md

This file guides Claude Code (and human contributors) when working in this repository.

## Project overview

BidTix is a backend-focused portfolio project: a concert ticketing platform with two sales channels.

1. **Booking** – fixed-price sales. Events have *zones* of two kinds:
   - `SEATED`: numbered seats; a user picks specific seats, which are *held* for a short payment window.
   - `GENERAL_ADMISSION` (GA): no seat selection; inventory is a counter that is atomically decremented.
2. **Auction** – English (ascending) auctions for single premium tickets, with minimum bid increment,
   anti-sniping (deadline extension), a payment deadline for the winner and second-chance offers to the
   next-highest bidder when the winner does not pay.

The point of the project is to demonstrate correct behaviour under **high concurrency**: no overselling,
no double-sold seats, no lost bids, safe retries (**idempotency**), clear **transaction** boundaries and
**caching** that never compromises correctness.

Full data model and concurrency strategy: [`docs/database-schema.md`](docs/database-schema.md).
Why the booking code is shaped the way it is: [`docs/ADR/`](docs/ADR) (read these before changing the
seat-hold or payment flow).

## Tech stack

- Java 21, Spring Boot 4.1.2 (webmvc, data-jpa, security, oauth2-resource-server, actuator)
- PostgreSQL (source of truth), Flyway (migrations); Redis is used as a cache (Spring Cache, `RedisCacheManager`,
  config in `CacheConfig` and `spring.cache.redis.*`); the booking fast-reject gate (`BookingGateway`) and rate limiting also use it
- Self-issued JWT (access + rotating refresh token); the app verifies its own tokens as a resource server
- Payment: VNPAY (sandbox by default), redirect to VNPAY's page and an asynchronous server-to-server callback (IPN).
  Settings come from `VNP_TMNCODE`, `VNP_HASH_SECRET`, `VNP_URL`, `VNP_RETURN_URL` (`bidtix.payment.vnpay.*`); the IPN
  URL is configured on VNPAY's side (`VNP_IPN_URL` is bound but not read by the code)
- Lombok, springdoc-openapi (Swagger UI); the Micrometer Prometheus registry is on the classpath but its
  endpoint is not exposed yet
- Tests: JUnit 5, Mockito, MockMvc, Spring Security test, Testcontainers (real Postgres; Docker is
  required to run the integration tests; `PostgresIntegrationTest` also starts a Redis container).

> Status: schema (Flyway V1–V19), JPA entities, the `auth` module, global error handling, Testcontainers
> and the booking flow for **SEATED zones** (`BookingService.bookTicket`: hold seats) and **GENERAL_ADMISSION zones**
> (counter decrement) are done. Booking does not take payment: `POST /api/v1/orders` (CUSTOMER, `OrderController`, 201 with
> the order, status PENDING_PAYMENT) needs an `Idempotency-Key` header (see **Idempotency**). The customer then pays with
> `POST /api/v1/orders/{orderCode}/payments` (VNPAY URL, same header), and VNPAY's callback `GET /api/v1/payments/vnpay/ipn`
> marks the order PAID and issues the tickets (see **Payment flow**).
> Role-protected management endpoints exist (`@PreAuthorize`, roles come from the JWT `roles` claim):
> `POST /venues` (ADMIN), `POST /events`, `POST /events/{id}/zones` (ORGANIZER; the zone call creates a
> SEATED zone's seats in bulk) and `POST /events/{id}/publish` (ORGANIZER, owner only, DRAFT to PUBLISHED, needs a
> zone), `GET /admin/events/{id}/tickets` (ADMIN). Any logged-in user can read `GET /venues/{publicId}` and
> `GET /events?page=&size=` (published events that have not started, soonest first); both are cached in Redis with
> a short TTL, and publishing evicts the event pages. Only CUSTOMERs can register, so ADMIN/ORGANIZER users must be
> inserted by SQL for now.
> Reading for buyers: `GET /events/{id}` (event with zones and what is left in each) and
> `GET /events/{id}/zones/{zoneId}/seats` (seat map; a draft is visible to its organizer only, 404 for others; neither is
> cached), `GET /orders`, `GET /orders/{code}` and `GET /tickets` (CUSTOMER, own rows only).
> Managing: `GET /events/mine` (ORGANIZER, drafts included); `PUT /events/{id}` (a draft entirely, a published event only
> title and description; evicts the event pages); `DELETE /events/{id}` and `PUT`/`DELETE /events/{id}/zones/{zoneId}`
> (drafts only, refused once there are orders; a standing zone never shrinks below what is taken). Creating, changing and
> removing zones, deleting and publishing lock the event row (`findByPublicIdForUpdate`). `GET /venues` (logged in),
> `PUT`/`DELETE /venues/{id}` (ADMIN; `VENUE_IN_USE` while an event uses it; both evict `CacheConfig.VENUES`).
> `GET /auctions/{id}`, `GET /auctions/{id}/bids` (history with bidder names), and `POST /auctions/{id}/cancel`
> (ORGANIZER, owner; only SCHEDULED or LIVE without bids, one conditional UPDATE, ticket back on sale).
> `POST /api/v1/auctions` (ORGANIZER, owner of a PUBLISHED event) creates a SCHEDULED auction for
> one seat or one standing ticket and takes that ticket out of sale in the same transaction (`AuctionService.createAuction`);
> `GET /api/v1/auctions?status=&eventPublicId=&page=&size=` (any logged-in user) lists auctions soonest ending first, open ones (SCHEDULED, LIVE) by default, with the public ids of the auction, event, zone and seat; it is not cached because price and bid count change on every bid. `AuctionActivationJob` (every 5 s) opens it, SCHEDULED to LIVE, once `starts_at` has passed (one conditional UPDATE with
> `FOR UPDATE SKIP LOCKED`; there is no manual "start" endpoint, so a bid is only accepted on a LIVE auction).
> `AuctionClosingJob` (every minute, one transaction per auction) closes a LIVE auction whose `ends_at` has passed: a winner (price and
> at least the reserve) makes it AWAITING_PAYMENT with an AUCTION order and a rank-1 offer that share one deadline; no bid or a price
> under the reserve makes it FAILED and puts the ticket back on sale (seat AVAILABLE, or +1 on the zone counter) and clears the gate flags.
> `AuctionOfferExpiryJob` (every minute, one transaction per offer) handles an unpaid offer past its deadline (plus the grace period when a payment is pending, see **Grace period**): it cancels the AUCTION order (the
> only thing that does), and the right to buy passes to the highest bidder who has not had an offer, at their own highest bid and if it reaches
> the reserve; with nobody left the auction FAILS like above. An order paid at the last moment makes the offer ACCEPTED and the auction SETTLED.
> The shared steps (open an offer, fail an auction) are in `AuctionSettlementService`.
> `POST /api/v1/auctions/{auctionPublicId}/bids` (CUSTOMER, body `{amount}`) places a bid (`BidService.placeBid`): a Java pre-check on the
> auction row refuses an obviously bad bid, then one conditional `UPDATE` (`AuctionRepository.placeBid`) decides status, end time and minimum
> price, moves price, leader and bid count, and extends `ends_at` inside the anti-sniping window; its row count is checked. It needs an
> `Idempotency-Key` header (see **Idempotency**).
> `GET /api/v1/auctions/{auctionPublicId}/leaderboard` (any logged-in user, `LeaderboardService`) lists the top bidders (`bidtix.leaderboard.top-n`, default 10),
> highest bid first, each once with their highest bid and their name. The list is a Redis ZSET per auction (`auction:leaderboard:{publicId}`, member = bidder id,
> score = highest bid, `AuctionLeaderBoard`; short TTL, default 60 s). A read that finds nothing in Redis for an auction that has bids rebuilds it from the
> database (`BidRepository.findTopBids`, which reads every bid of the auction) with `ZADD GT`; a committed bid (`BidPlacedEvent`, `@TransactionalEventListener(AFTER_COMMIT)`)
> raises that bidder's score only when the ZSET already exists (a Lua script), because a ZSET made of one bidder would look complete and never be rebuilt. Redis down:
> reads fall back to the database and writes are ignored (fail-open).
> `GET /api/v1/payments/vnpay/return` (public, signed, rate limit `vnpay-return`) shows the state of a payment and changes
> nothing.
> Not started: cancelling an event, enforcing `max_tickets_per_user`, ticket check-in, and querying VNPAY for payments left
> PENDING. Refunds have not been seen to work against the VNPAY sandbox. Rate limits exist for login, register, the VNPAY callback and
> logged-in callers; any other public route needs `@RateLimited` when it is added. Cache rule: whatever adds or removes events from the public list, or changes what it shows, (a future cancel) must evict
> `CacheConfig.EVENTS`, and whatever changes a venue must evict `CacheConfig.VENUES`.

**Entity mapping conventions**
- `BaseEntity` (id + `created_at`) / `AuditableEntity` (+ `updated_at`) in `common/domain`.
- Within a module, relations are `@ManyToOne(fetch = LAZY)`. Across modules, store only the id
  (`Long userId`, `Long eventId`, ...) so modules stay decoupled.
- Entities have a protected no-arg constructor, a public constructor for required fields, and getters
  only. State changes go through intent-revealing domain methods, not setters.
- `publicId` is generated in Java (`UUID.randomUUID()`), and the DB default is only a fallback.

## Commands

`make help` lists shortcuts for everything below (`make run-local`, `make test`, `make test-one T=ClassName`,
`make keys`, `make psql`, `make bench`, ...). `make bench` runs the slow anti-overselling benchmark
([`docs/benchmarks.md`](docs/benchmarks.md)); tests tagged `benchmark` are skipped by `make test`.

`make load-test [PROFILE=smoke|read|book|mixed|users|all]` (`loadtest/run.sh`) load tests the whole application over HTTP:
it builds the `Dockerfile` image, runs its own compose stack (`loadtest/compose.yaml`, fresh database, fixed CPU limits),
signs tokens with a throwaway key and drives k6. `make sale-day` (`loadtest/soak.sh`, `realistic.js`) runs 30 minutes of
simulated buyers and bidders with real logins and self-signed IPN callbacks, then checks the data. Method and results:
[`docs/benchmarks.md`](docs/benchmarks.md), sections "Load test through the API" and "Sale day". A change to the schema or
to a route they call may need the seed files or the k6 scripts in `loadtest/` updated.

The raw commands:

```bash
./mvnw spring-boot:run                 # run app; spring-boot-docker-compose starts services from compose.yaml
./mvnw test                            # all tests (needs Docker: integration tests start PostgreSQL)
./mvnw test -Dtest=ClassName#method    # single test
./mvnw clean package                   # build jar
```

**CI** (`.github/workflows/ci.yml`): every pull request into `main`, and every push to `main`, runs `make keys` (the JWT keys are git-ignored and
the app will not start without them) and `./mvnw -B -ntp verify` on `ubuntu-latest` (Docker is there, so the Testcontainers tests run). The benchmarks are
tagged `benchmark` and skipped. To reproduce a CI run locally: `make keys && ./mvnw -B -ntp verify` (Docker needed). A failed run keeps
`target/surefire-reports/` as an artifact. `bidtix.scheduling.enabled=false` turns the `@Scheduled` jobs off; only a test that would race with a
background job sets it (see `BookingGateRedisDownTest`).

Swagger UI: `http://localhost:8080/swagger-ui/index.html` · OpenAPI: `/v3/api-docs`. Both are public,
as are `/api/v1/auth/**` and the VNPAY callback routes `/api/v1/payments/vnpay/{ipn,return}`; every other route (including `/actuator/**`, where only `health` is exposed)
needs a Bearer token.

**First-time setup: JWT signing keys** (the app will not start without them; `keys/` is git-ignored)
```bash
mkdir -p keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/private.pem   # PKCS#8
openssl pkey -in keys/private.pem -pubout -out keys/public.pem
```
Override the locations with `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` (e.g. `file:/run/secrets/jwt.pem`).
The refresh cookie is `Secure`, which browsers/curl only send over https; for plain-http local testing
run with `BIDTIX_AUTH_REFRESHCOOKIE_SECURE=false`.

**Auth flow** (`/api/v1/auth/**`, all public): `register` → `login` returns the access JWT (RS256,
5 min) in the body and the refresh token in an `HttpOnly; SameSite=Strict; Path=/api/v1/auth` cookie →
`refresh` reads that cookie and rotates it (old token revoked, new one issued) → `logout` revokes it and
clears the cookie. The database stores only the SHA-256 hash of a refresh token. Presenting an already
used token revokes all of that user's sessions (theft response); two parallel refreshes with the same
token cannot both win (conditional `UPDATE ... WHERE revoked_at IS NULL`).

**Booking flow** (`BookingService.bookTicket`, SEATED and GENERAL_ADMISSION zones, `POST /api/v1/orders`).
Decisions and alternatives: [ADR-0001](docs/ADR/0001-hold-seats-with-a-conditional-update.md) (seats) and
[ADR-0003](docs/ADR/0003-take-general-admission-stock-with-a-conditional-update.md) (standing stock). The Redis refusal gate in front of it: [ADR-0004](docs/ADR/0004-refuse-known-sold-out-requests-with-a-redis-flag.md). At most
`bidtix.booking.max-tickets-per-order` (5) tickets per order, and the zone must belong to the event. Only a PUBLISHED
event sells (anything else is `EVENT_NOTFOUND`), and only from `sale_starts_at` until before `sale_ends_at`
(`EVENT_NOT_ON_SALE`). The
method runs one transaction with `TransactionTemplate` (the gate check and its flags stay outside it) and returns a
`BookingTicketSeatsResult` or a `BookingTicketQuantityResult` (both are a `BaseBookingTicketResult`), PENDING_PAYMENT and
without tickets:
- `reserve` (transaction): validate, insert a `PENDING_PAYMENT` order, then take the stock with one
   conditional `UPDATE` whose row count is checked. SEATED: hold the seats, row count must equal the number
   of seats (ids sorted ascending); a short count throws `SEAT_UNAVAILABLE` with the lost `seatPublicIds`.
   GA: subtract the quantity from `available_quantity` where it is `>=` the quantity (the last write, because
   it locks the hot counter row); 0 rows throws `ZONE_SOLD_OUT`. Either way everything rolls back.
- The stock stays taken until the order is paid (see **Payment flow**) or expires.

**Booking gate** (`booking/infrastructure/BookingGateway`, `bidtix.booking.gate-enabled` / `gate-ttl-seconds`): before `reserve`,
`BookingService.rejectIfKnownUnavailable` asks Redis whether a recent request already learned that this quantity (GA) or these
seats cannot be had, and refuses without opening a transaction. The flags are written only after the database refused
(`rememberRejection`), live for a few seconds and can only refuse: a missing flag, an expired one or Redis down lets the request
through (fail-open), and the conditional `UPDATE` still decides. Measured effect: `docs/benchmarks.md`, section "Booking gate on
the real flow". **Whatever returns stock (the hold-expiry sweeper, giving GA quantity back, a cancel) must clear the matching
flags after its database commit**, or customers are refused for up to the TTL.

**Grace period** (`payment/service/ConfirmationGrace`, `bidtix.payment.confirmation-grace`, 10 m): an expired order with a
`PENDING` payment is released only after the grace period, by both expiry jobs, because the provider's callback may still be
on its way. The customer gets no extra time to pay. Whatever else releases the stock of an unpaid order must ask it too.

**Unpaid orders** (`booking/infrastructure/OrderCancellationJob`, every minute after the last run ended): batches of 100 expired
`PENDING_PAYMENT` **booking** order ids are read, and each batch is one transaction: lock the orders with `FOR UPDATE SKIP LOCKED`,
set them `CANCELLED`, stock back in the same transaction (seats `HELD -> AVAILABLE`, GA quantity added to the counter). A batch that
fails rolls back as a whole and is retried one order per transaction, so one bad order never blocks the others. After each commit it
clears the gate flags of the zones and seats that got stock back. Auction orders (`source = 'AUCTION'`) are never touched: the auction
owns its stock, and returning it here as well would return it twice (`AuctionOfferExpiryJob` cancels those orders). The status is `CANCELLED`, not `EXPIRED` (the enum value is unused).
Why batches and not one transaction per order: [`docs/benchmarks.md`](docs/benchmarks.md), section "Cancelling unpaid orders".

**Payment flow** (`payment` module; decisions: [ADR-0005](docs/ADR/0005-confirm-vnpay-payments-in-the-ipn-callback.md)).
The module sees orders only through `PayableOrders`, which `booking` implements (`OrderPayment`).
1. `POST /api/v1/orders/{orderCode}/payments` (CUSTOMER): `PaymentService.createUrlPayment` commits a `PENDING` payment with
   a new `txn_ref` and returns the signed VNPAY URL, which closes no later than the order's deadline. `requested_at` keeps the
   exact `vnp_CreateDate` sent (VNPAY's query and refund APIs ask for it).
2. `GET /api/v1/payments/vnpay/ipn` (public, signed, rate limit `vnpay-ipn`; `VnpayCallbackController`):
   `processVnPayResponse` checks the signature (`97`), stores the callback in `payment_webhook_events` (own transaction),
   then in one transaction finds the payment (`01`), compares the amount (`04`), requires `PENDING` (`02`) and records the
   result (`00`): `FAILED`, or `applyPayment` (order `PAID`, seats `HELD -> SOLD`, tickets issued, auction offer accepted and
   auction settled) with `SUCCEEDED`, or `REFUND_PENDING` when the order cannot take the money. Anything unexpected: `99`.
   VNPAY retries unless it gets `00` or `02`. The answer is `TransactionResponse` (`RspCode`, `Message`), never a
   `ProblemDetail` once the request reaches the service.

Refunds (`payment/infrastructure/RefundPaymentJob`, `bidtix.payment.refund-cron`, every minute in `application.yaml`; `-` turns it off, which
`PostgresIntegrationTest` does): batches of `REFUND_PENDING` payments are locked (`SKIP LOCKED`) and claimed with
`markRefundRequested` (1 row = yours), committed, then VNPAY is called outside any transaction. The claim is given back
(`releaseRefundClaim`) only when the request certainly never reached VNPAY (it could not be built, or no connection was
made): retried after 1, 5, then 30 minutes, 5 attempts in all. A read timeout, a broken connection, an HTTP error or any
answer keeps the claim, so nothing is sent twice; the problem is written to `failure_reason` after `[refund]`. `handleRefundResponse` checks the signature, `vnp_ResponseCode`, then
`vnp_TransactionStatus`, and records refusals. When VNPAY has the refund (`00` with status `00`/`05`/`06`, or `94`) the job
asks querydr at once; `confirmRefunds` (same cron) asks again about claimed refunds older than a minute, including those
with an unknown outcome (`claimRefundQuery`; waits 1 m, 5 m, 30 m, 2 h, then 12 h; 10 queries at most; none after a
rejection). Only querydr marks a payment `REFUNDED`: a full-refund record (type `02`) with status `00`, our reading of
VNPAY's documentation that no real answer has confirmed yet (the sandbox answers `99`). A failed query changes nothing and
never gives the refund claim back.

Rules for changing it: the payment's conditional `UPDATE` (row count checked) is the guard against processing twice, never
the stored event; no refund or other VNPAY call inside the request's transaction; only the IPN confirms a payment, never the
return URL. Open points: refunds not yet verified on the sandbox, nothing scans events with `processed_at IS NULL` or asks VNPAY
about payments left `PENDING`.

## Architecture

BidTix is a **modular monolith**: one deployable Spring Boot app and one PostgreSQL database, split into
business modules by feature (package-by-feature). Inside each module the code is a classic **layered
architecture**. It is deliberately *not* microservices and *not* hexagonal/clean: entities are JPA
entities and services call Spring Data repositories directly. Do not add ports/adapters or extra
abstraction layers unless a real need appears.

```
Request path:  security filters (JWT) -> controller -> service (@Transactional) -> repository -> PostgreSQL
Errors:        any layer throws -> GlobalExceptionHandler (one place, one JSON format)
```

**Layer rules** (inside a module)
- `controller` calls `service`; `service` calls `infrastructure` and `domain`. Controllers never touch
  repositories or entities (they use DTOs); `domain` never depends on `controller` or `service`.
- Business rules and transactions live in `service`. Controllers only translate HTTP to service calls and back.

**Module rules**
- A module may depend on `common`. Entities in different modules reference each other by id only (no JPA
  relations across modules), although the database still has the foreign keys.
- No dependency cycles between modules. Today: `auth -> user, common, config`, `config -> user`, and `booking -> payment`
  (it implements `PayableOrders`; `payment` never imports `booking`).
- Known exception: `auth` uses the `user` module's `UserRepository` and `User` entity directly. When `user`
  gets its own service, move that access behind it. For new code, go through the other module's `service`.
- Nothing enforces these rules automatically yet (ArchUnit or Spring Modulith would), so check them in review.

## Package structure

Package-by-feature under `com.bidtix`:

```
auth/      controller, service, infrastructure, dtos, domain   done: register / login / refresh / logout
user/      domain, infrastructure                              entity + repository only, no API yet
event/     controller, dtos, service, infrastructure, domain   venue / event / zone+seats creation (organizer, admin)
booking/   controller, domain, dtos, service, infrastructure    booking flow (seated + GA), POST /orders, sweeper, paying orders
auction/   controller, dtos, service, infrastructure, domain    create + activation + closing + offer expiry + bids done
payment/   controller, dtos, service, infrastructure, domain    VNPAY payment URL + IPN callback + webhook inbox (no refunds yet)
common/    domain (BaseEntity), utils, idempotency             shared by every module
config/    security, exceptions, ClockConfig                   cross-cutting Spring setup, see below
```

Layers inside a feature (create only the ones that are needed):

- `controller/` – REST controllers and web-only helpers (e.g. the cookie builder); no business logic
- `dtos/` – request/response records (never expose entities)
- `service/` – business logic; **the only layer that declares `@Transactional`**
- `domain/` – JPA entities, enums, domain rules
- `infrastructure/` – Spring Data repositories, Redis adapters, external clients, `@ConfigurationProperties`

`config/` holds cross-cutting setup rather than a business feature: `config/security` (filter chain,
JWT encoder/decoder and `JwtUtil`, `PasswordEncoder`, `AuthenticationManager`, `UserPrincipal`),
`config/exceptions` (`ErrorCode`, `BusinessException`, `GlobalExceptionHandler`) and `ClockConfig` (the
`Clock` bean; inject it instead of calling `Instant.now()` so tests can fix the time).

## Rules (must follow)

**Schema & data**
- Schema changes only via Flyway, one folder per module:
  `src/main/resources/db/migration/<module>/V{n}__description.sql`. Version numbers are global
  across modules, so always take the next free number. Hibernate runs with `ddl-auto=validate`.
  Never edit an already-applied migration; add a new one.
- Money is `BIGINT` in the smallest currency unit (VND). Never `float`/`double`; use `long` in Java.
- Time is `TIMESTAMPTZ` in the DB and `Instant` in Java. Strings are `VARCHAR`, never `CHAR`.
- Enums are stored as `VARCHAR` + `CHECK` constraint and mapped with `@Enumerated(EnumType.STRING)`.
- APIs identify resources by `public_id` (UUID), never by the internal `BIGINT id`.
- Every FK column gets an index (unless it leads a `UNIQUE` constraint). Other indexes are added only
  when a real query needs them; see "Deferred" in `docs/database-schema.md`.

**Consistency & concurrency**
- PostgreSQL is the source of truth. Redis may only hold cache, fast-reject gates, rate-limit
  counters and the auction leaderboard cache; it must never be the only record of money, tickets or bids.
- State transitions use a conditional update (`UPDATE ... WHERE id = ? AND status = ?`) or optimistic
  locking (`@Version`), and the code **must check the affected row count**.
- Lock/update multiple rows in ascending `id` order to avoid deadlocks.
- Keep transactions short. No external I/O (payment provider calls, HTTP, long Redis locks) inside a
  DB transaction.
- Background jobs (hold expiry, auction closing, offer expiry) select work with
  `FOR UPDATE SKIP LOCKED` so multiple app instances can run them safely.
- DB constraints are the last line of defence (unique indexes, `CHECK`s). Do not remove them to "fix"
  a failing operation; handle the constraint violation instead.

**Errors**
- Services throw `BusinessException(ErrorCode.X)`; they never build HTTP responses. Add new errors to
  `ErrorCode` (the enum name is the stable `code` clients branch on, so never rename a released one).
- `GlobalExceptionHandler` (`config/exceptions`) is the only place exceptions become responses. The
  format is RFC 9457 `ProblemDetail` plus a `code` field. 401/403 from the security filters go through
  `RestAuthenticationEntryPoint` / `RestAccessDeniedHandler`, which delegate to the same handler.
- Never return stack traces, SQL or constraint names to the client (log them). Wrong password, unknown
  email and locked account must return the same `INVALID_CREDENTIALS` response.
- Catch `DataIntegrityViolationException` for known constraints and throw a specific
  `BusinessException` (e.g. `SEAT_UNAVAILABLE`); the generic 409 handler is only a safety net.
- A `BusinessException` can carry extra response fields:
  `new BusinessException(code, detail, Map.of("seatPublicIds", ids))`. They are added next to `code` in
  the body, so clients can use them (never put internal ids or SQL in them).

**Idempotency**
- `POST /orders`, `POST /auctions/{id}/bids` and `POST /orders/{code}/payments` require an
  `Idempotency-Key` header (stored in `idempotency_keys`; same key + different body returns 422,
  in-flight duplicate returns 409, completed duplicate replays the stored response).
- Payment webhooks are stored once per `(provider, provider_event_id)` in `payment_webhook_events`; processing them twice is
  prevented by the payment's status (conditional `UPDATE`), not by that row (see **Payment flow**).
- `IdempotencyFilter` (`common/idempotency`) enforces it. It runs after the Spring Security chain (keys belong to a user) and
  only on the POST routes listed in `bidtix.idempotency.paths` (today `/api/v1/auctions/*/bids`, `/api/v1/orders` and
  `/api/v1/orders/*/payments`). It claims the key with `INSERT ... ON CONFLICT DO NOTHING` (committed before the request runs), runs the
  request and stores the response **only for a 2xx**; a failure deletes the key so a retry runs again. Missing or too long header:
  400; same key, other body: 422 `IDEMPOTENCY_KEY_REUSED`; duplicate still running: 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS`; a replay
  carries `Idempotent-Replayed: true`. A key stuck `PROCESSING` for `processing-timeout` is taken as abandoned. The claim and the
  stored response are NOT in the business transaction, so a crash between the business commit and storing the response lets the
  same request run once more after the timeout: a flow whose repeat is harmful needs its own guard in the business transaction.

**Caching**
- Cache-aside with explicit TTLs; evict on write. Cached availability is a hint for reads only; every
  purchase or bid is validated against the DB.
- A `@Cacheable` that can be hit by many requests at once uses `sync = true`. That only works because `CacheConfig`
  uses a locking cache writer (with the default writer, 16 simultaneous requests caused 16 DB loads).

**Rate limiting** (`config/ratelimit`)
- Counters live in Redis (one Lua script each), configured under `bidtix.rate-limit.policies` in application.yaml.
  A policy is `fixed-window` (`limit`, `window`) or `token-bucket` (`capacity`, `refill-tokens`, `refill-every`; the
  refill is worked out when a request arrives, there is no background job), plus `fail-open`. Today: login and
  register are fixed windows per IP, `default-user` is a bucket of 15 tokens refilled by 3 every 10 s, `vnpay-ipn` is a wide
  fail-open bucket per IP (100, refilled by 20 every second). `RateLimitInterceptor` runs after Spring Security, on `/api/**`: a controller
  method or class with `@RateLimited(policy = ..., by = IP|USER)` uses that policy; otherwise a logged-in caller gets
  `default-user`. Too many requests: 429 `TOO_MANY_REQUESTS` with `Retry-After`; Redis unreachable: fail-open policies
  let the request through, fail-closed ones answer 503 `RATE_LIMITER_UNAVAILABLE` (keep login and register fail-closed).
- An endpoint that needs no login must have `@RateLimited` (by IP): without it nothing limits it. Behind a proxy set
  `server.forward-headers-strategy`, or every caller shares the proxy's IP.

**Testing**
- Every concurrency-sensitive flow needs an integration test with Testcontainers that fires N parallel
  requests (`ExecutorService` + `CountDownLatch`) and asserts invariants (e.g. sold ≤ capacity, one
  ticket per seat, final auction price = max accepted bid).
- Integration tests extend `PostgresIntegrationTest` (`src/test/.../support`): the full application
  against one shared PostgreSQL container, with Flyway building the schema. Each test creates its own
  data (see `BookingConcurrencyTest.fixture`) and must not assume an empty database. Docker is required.
- Check that a concurrency test can fail: remove the guard under test (e.g. the `status = 'AVAILABLE'`
  condition of the seat-hold `UPDATE`) and confirm the test goes red before trusting it.

## Domain glossary

| Term | Meaning |
|---|---|
| Zone | Ticket category inside an event (`SEATED` or `GENERAL_ADMISSION`) with one price |
| Seat | A numbered seat in a `SEATED` zone |
| Hold | Temporary reservation of seats/quantity while an order awaits payment |
| Order | A purchase attempt (`BOOKING` or `AUCTION` source) with a payment deadline |
| Ticket | Issued only after successful payment; its `public_id` is the QR payload |
| Auction | English auction selling one ticket; inventory is reserved when it is created |
| Bid | An accepted bid; amounts strictly increase by at least `min_increment` |
| Offer | Right to buy an auction ticket at the bid price within a deadline (rank 1 = winner, then second-chance) |
| Idempotency key | Client-supplied key that makes retrying a write request safe |
