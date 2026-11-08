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

## Tech stack

- Java 21, Spring Boot 4.1.2 (webmvc, data-jpa, security, oauth2-resource-server, actuator)
- PostgreSQL (source of truth), Flyway (migrations); Redis (cache / fast-path gate / rate limit) is planned
- Self-issued JWT (access + rotating refresh token); the app verifies its own tokens as a resource server
- Mock payment provider with asynchronous webhook callbacks (planned)
- Lombok, springdoc-openapi (Swagger UI); the Micrometer Prometheus registry is on the classpath but its
  endpoint is not exposed yet
- Tests: JUnit 5, Mockito, MockMvc, Spring Security test; Testcontainers (real Postgres + Redis) is planned

> Status: schema (Flyway V1–V12), JPA entities, the `auth` module and global error handling are done.
> Everything else (events API, booking, auction, payment, idempotency filter) is so far only schema and
> entities. Redis and Testcontainers are planned but not yet in `pom.xml` / `compose.yaml`; add them
> before writing code that depends on them. Until Testcontainers exists,
> `BidtixBackendApplicationTests.contextLoads` fails (tests have no database); every other test passes.

**Entity mapping conventions**
- `BaseEntity` (id + `created_at`) / `AuditableEntity` (+ `updated_at`) in `common/domain`.
- Within a module, relations are `@ManyToOne(fetch = LAZY)`. Across modules, store only the id
  (`Long userId`, `Long eventId`, ...) so modules stay decoupled.
- Entities have a protected no-arg constructor, a public constructor for required fields, and getters
  only. State changes go through intent-revealing domain methods, not setters.
- `publicId` is generated in Java (`UUID.randomUUID()`), and the DB default is only a fallback.

## Commands

`make help` lists shortcuts for everything below (`make run-local`, `make test`, `make test-one T=ClassName`,
`make keys`, `make psql`, ...). The raw commands:

```bash
./mvnw spring-boot:run                 # run app; spring-boot-docker-compose starts services from compose.yaml
./mvnw test                            # all tests (contextLoads fails until Testcontainers is added)
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
- `controller` calls `service`; `service` calls `repository` and `domain`. Controllers never touch
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
event/     domain                                              venues, events, ticket zones, seats
booking/   domain                                              orders, items, tickets (holds + expiry sweeper planned)
auction/   domain                                              auctions, bids, offers (closing job planned)
payment/   domain                                              payments, webhook inbox (mock provider planned)
common/    domain (BaseEntity), idempotency                    shared by every module
config/    security, exceptions                                cross-cutting Spring setup, see below
```

Layers inside a feature (create only the ones that are needed):

- `controller/` – REST controllers and web-only helpers (e.g. the cookie builder); no business logic
- `dtos/` – request/response records (never expose entities)
- `service/` – business logic; **the only layer that declares `@Transactional`**
- `domain/` – JPA entities, enums, domain rules
- `infrastructure/` – Spring Data repositories, Redis adapters, external clients, `@ConfigurationProperties`

`config/` holds cross-cutting setup rather than a business feature: `config/security` (filter chain,
JWT encoder/decoder and `JwtUtil`, `PasswordEncoder`, `AuthenticationManager`, `UserPrincipal`) and
`config/exceptions` (`ErrorCode`, `BusinessException`, `GlobalExceptionHandler`).

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

**Idempotency**
- `POST /orders`, `POST /auctions/{id}/bids` and `POST /orders/{code}/payments` require an
  `Idempotency-Key` header (stored in `idempotency_keys`; same key + different body returns 422,
  in-flight duplicate returns 409, completed duplicate replays the stored response).
- Payment webhooks are deduplicated by `(provider, provider_event_id)` in `payment_webhook_events`.

**Caching**
- Cache-aside with explicit TTLs; evict on write. Cached availability is a hint for reads only; every
  purchase or bid is validated against the DB.

**Testing**
- Every concurrency-sensitive flow needs an integration test with Testcontainers that fires N parallel
  requests (`ExecutorService` + `CountDownLatch`) and asserts invariants (e.g. sold ≤ capacity, one
  ticket per seat, final auction price = max accepted bid).

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
