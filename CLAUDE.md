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
- Payment: `PaymentGateway` interface with a `MockPaymentGateway` that always succeeds (synchronous);
  the real provider with asynchronous webhook callbacks is planned
- Lombok, springdoc-openapi (Swagger UI); the Micrometer Prometheus registry is on the classpath but its
  endpoint is not exposed yet
- Tests: JUnit 5, Mockito, MockMvc, Spring Security test, Testcontainers (real Postgres; Docker is
  required to run the integration tests; `PostgresIntegrationTest` also starts a Redis container).

> Status: schema (Flyway V1–V14), JPA entities, the `auth` module, global error handling, Testcontainers
> and the booking flow for **SEATED zones** (`BookingService.bookTicket`: hold seats, mock payment, issue
> tickets) are done, and so is the flow for **GENERAL_ADMISSION zones** (counter decrement). The booking
> flow is service-only: there is no controller or endpoint for it yet.
> Role-protected management endpoints exist (`@PreAuthorize`, roles come from the JWT `roles` claim):
> `POST /venues` (ADMIN), `POST /events`, `POST /events/{id}/zones` (ORGANIZER; the zone call creates a
> SEATED zone's seats in bulk) and `POST /events/{id}/publish` (ORGANIZER, owner only, DRAFT to PUBLISHED, needs a
> zone), `GET /admin/events/{id}/tickets` (ADMIN). Any logged-in user can read `GET /venues/{publicId}` and
> `GET /events?page=&size=` (published events that have not started, soonest first); both are cached in Redis with
> a short TTL, and publishing evicts the event pages. Only CUSTOMERs can register, so ADMIN/ORGANIZER users must be
> inserted by SQL for now. `POST /api/v1/auctions` (ORGANIZER, owner of a PUBLISHED event) creates a SCHEDULED auction for
> one seat or one standing ticket and takes that ticket out of sale in the same transaction (`AuctionService.createAuction`);
> `GET /api/v1/auctions?status=&eventPublicId=&page=&size=` (any logged-in user) lists auctions soonest ending first, open ones (SCHEDULED, LIVE) by default, with the public ids of the auction, event, zone and seat; it is not cached because price and bid count change on every bid. `AuctionActivationJob` (every 5 s) opens it, SCHEDULED to LIVE, once `starts_at` has passed (one conditional UPDATE with
> `FOR UPDATE SKIP LOCKED`; there is no manual "start" endpoint, so a bid is only accepted on a LIVE auction).
> `AuctionClosingJob` (every minute, one transaction per auction) closes a LIVE auction whose `ends_at` has passed: a winner (price and
> at least the reserve) makes it AWAITING_PAYMENT with an AUCTION order and a rank-1 offer that share one deadline; no bid or a price
> under the reserve makes it FAILED and puts the ticket back on sale (seat AVAILABLE, or +1 on the zone counter) and clears the gate flags.
> `AuctionOfferExpiryJob` (every minute, one transaction per offer) handles an unpaid offer past its deadline: it cancels the AUCTION order (the
> only thing that does), and the right to buy passes to the highest bidder who has not had an offer, at their own highest bid and if it reaches
> the reserve; with nobody left the auction FAILS like above. An order paid at the last moment makes the offer ACCEPTED and the auction SETTLED.
> The shared steps (open an offer, fail an auction) are in `AuctionSettlementService`.
> Not started: event cancel and update, the rest of the auction (bids, cancel), real payment provider and webhooks,
> idempotency filter. Rate limits exist for login, register and logged-in callers; the other public routes need
> `@RateLimited` when they are added. Cache rule: whatever adds or removes events from the public list (a future cancel) must evict
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
([`docs/benchmarks.md`](docs/benchmarks.md)); tests tagged `benchmark` are skipped by `make test`. The raw commands:

```bash
./mvnw spring-boot:run                 # run app; spring-boot-docker-compose starts services from compose.yaml
./mvnw test                            # all tests (needs Docker: integration tests start PostgreSQL)
./mvnw test -Dtest=ClassName#method    # single test
./mvnw clean package                   # build jar
```

Swagger UI: `http://localhost:8080/swagger-ui/index.html` · OpenAPI: `/v3/api-docs`. Both are public,
as is `/api/v1/auth/**`; every other route (including `/actuator/**`, where only `health` is exposed)
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

**Booking flow** (`BookingService.bookTicket`, SEATED and GENERAL_ADMISSION zones, no HTTP endpoint yet).
Decisions and alternatives: [ADR-0001](docs/ADR/0001-hold-seats-with-a-conditional-update.md) (seats),
[ADR-0002](docs/ADR/0002-take-payment-outside-the-transaction.md) (payment boundary) and
[ADR-0003](docs/ADR/0003-take-general-admission-stock-with-a-conditional-update.md) (standing stock). The Redis refusal gate in front of it: [ADR-0004](docs/ADR/0004-refuse-known-sold-out-requests-with-a-redis-flag.md). At most
`bidtix.booking.max-tickets-per-order` (5) tickets per order, and the zone must belong to the event. The
method is deliberately **not** `@Transactional`; it runs three steps with `TransactionTemplate`, and returns a
`BookingTicketSeatsResult` or a `BookingTicketQuantityResult` (both are a `BaseBookingTicketResult`):
1. `reserve` (transaction): validate, insert a `PENDING_PAYMENT` order, then take the stock with one
   conditional `UPDATE` whose row count is checked. SEATED: hold the seats, row count must equal the number
   of seats (ids sorted ascending); a short count throws `SEAT_UNAVAILABLE` with the lost `seatPublicIds`.
   GA: subtract the quantity from `available_quantity` where it is `>=` the quantity (the last write, because
   it locks the hot counter row); 0 rows throws `ZONE_SOLD_OUT`. Either way everything rolls back.
2. `PaymentGateway.charge` (no transaction open). `MockPaymentGateway` always returns `true`. On `false`
   the method returns the `PENDING_PAYMENT` result and the stock stays taken until the order expires.
3. `completePayment` (transaction): order `PAID`; SEATED seats `HELD -> SOLD` (row count checked); tickets
   issued (one per seat, or one per unit of a GA item). Tickets exist only for paid orders.

**Booking gate** (`booking/infrastructure/BookingGateway`, `bidtix.booking.gate-enabled` / `gate-ttl-seconds`): before step 1,
`BookingService.rejectIfKnownUnavailable` asks Redis whether a recent request already learned that this quantity (GA) or these
seats cannot be had, and refuses without opening a transaction. The flags are written only after the database refused
(`rememberRejection`), live for a few seconds and can only refuse: a missing flag, an expired one or Redis down lets the request
through (fail-open), and the conditional `UPDATE` still decides. Measured effect: `docs/benchmarks.md`, section "Booking gate on
the real flow". **Whatever returns stock (the hold-expiry sweeper, giving GA quantity back, a cancel) must clear the matching
flags after its database commit**, or customers are refused for up to the TTL.

**Unpaid orders** (`booking/infrastructure/OrderCancellationJob`, every minute after the last run ended): batches of 100 expired
`PENDING_PAYMENT` **booking** order ids are read, and each batch is one transaction: lock the orders with `FOR UPDATE SKIP LOCKED`,
set them `CANCELLED`, stock back in the same transaction (seats `HELD -> AVAILABLE`, GA quantity added to the counter). A batch that
fails rolls back as a whole and is retried one order per transaction, so one bad order never blocks the others. After each commit it
clears the gate flags of the zones and seats that got stock back. Auction orders (`source = 'AUCTION'`) are never touched: the auction
owns its stock, and returning it here as well would return it twice (`AuctionOfferExpiryJob` cancels those orders). The status is `CANCELLED`, not `EXPIRED` (the enum value is unused).
Why batches and not one transaction per order: [`docs/benchmarks.md`](docs/benchmarks.md), section "Cancelling unpaid orders".

Open points: a real gateway needs a refund when step 3 fails after a successful charge (`TODO(payment)`).

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
- No dependency cycles between modules. Today: `auth -> user, common, config` and `config -> user`.
- Known exception: `auth` uses the `user` module's `UserRepository` and `User` entity directly. When `user`
  gets its own service, move that access behind it. For new code, go through the other module's `service`.
- Nothing enforces these rules automatically yet (ArchUnit or Spring Modulith would), so check them in review.

## Package structure

Package-by-feature under `com.bidtix`:

```
auth/      controller, service, infrastructure, dtos, domain   done: register / login / refresh / logout
user/      domain, infrastructure                              entity + repository only, no API yet
event/     controller, dtos, service, infrastructure, domain   venue / event / zone+seats creation (organizer, admin)
booking/   domain, dto, service, infrastructure                seated booking flow done; sweeper, GA, controller planned
auction/   controller, dtos, service, infrastructure, domain    create + activation + closing + offer expiry done; bids planned
payment/   domain, service, infrastructure                     PaymentGateway + mock; webhook inbox entity (real provider planned)
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

Known inconsistency: `booking` names its DTO package `dto/` for the booking flow but `dtos/` for the admin
ticket list (auth uses `dtos/`). New code follows the layer list above; move `dto/` when touching it.

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
- PostgreSQL is the source of truth. Redis may only hold cache, fast-reject gates, and rate-limit
  counters; it must never be the only record of money, tickets or bids.
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
- Payment webhooks are deduplicated by `(provider, provider_event_id)` in `payment_webhook_events`.

**Caching**
- Cache-aside with explicit TTLs; evict on write. Cached availability is a hint for reads only; every
  purchase or bid is validated against the DB.
- A `@Cacheable` that can be hit by many requests at once uses `sync = true`. That only works because `CacheConfig`
  uses a locking cache writer (with the default writer, 16 simultaneous requests caused 16 DB loads).

**Rate limiting** (`config/ratelimit`)
- Counters live in Redis (one Lua script each), configured under `bidtix.rate-limit.policies` in application.yaml.
  A policy is `fixed-window` (`limit`, `window`) or `token-bucket` (`capacity`, `refill-tokens`, `refill-every`; the
  refill is worked out when a request arrives, there is no background job), plus `fail-open`. Today: login and
  register are fixed windows per IP, `default-user` is a bucket of 15 tokens refilled by 3 every 10 s. `RateLimitInterceptor` runs after Spring Security, on `/api/**`: a controller
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
