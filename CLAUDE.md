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
- PostgreSQL (source of truth), Redis (cache / fast-path gate / rate limit), Flyway (migrations)
- Self-issued JWT (access + rotating refresh token); the app verifies its own tokens as a resource server
- Mock payment provider with asynchronous webhook callbacks
- Lombok, springdoc-openapi (Swagger UI), Micrometer + Prometheus
- Tests: JUnit 5, Spring Boot Test, Testcontainers (real Postgres + Redis)

> Status: schema (Flyway V1–V7) and JPA entities (`<module>/domain`) are in place. Redis and
> Testcontainers are planned but not yet in `pom.xml` / `compose.yaml`. Add them before writing code
> that depends on them.

**Entity mapping conventions**
- `BaseEntity` (id + `created_at`) / `AuditableEntity` (+ `updated_at`) in `common/domain`.
- Within a module, relations are `@ManyToOne(fetch = LAZY)`. Across modules, store only the id
  (`Long userId`, `Long eventId`, ...) so modules stay decoupled.
- Entities have a protected no-arg constructor, a public constructor for required fields, and getters
  only. State changes go through intent-revealing domain methods, not setters.
- `publicId` is generated in Java (`UUID.randomUUID()`), and the DB default is only a fallback.

## Commands

```bash
./mvnw spring-boot:run                 # run app; spring-boot-docker-compose starts services from compose.yaml
./mvnw test                            # all tests (needs Docker for Testcontainers)
./mvnw test -Dtest=ClassName#method    # single test
./mvnw clean package                   # build jar
```

Swagger UI: `http://localhost:8080/swagger-ui.html` · Metrics: `/actuator/prometheus`

## Package structure

Package-by-feature under `com.bidtix`:

```
auth/      login, JWT issuing, refresh tokens
user/      user accounts and roles
event/     venues, events, ticket zones, seats
booking/   orders, seat holds, GA reservations, tickets, hold-expiry sweeper
auction/   auctions, bids, offers, auction-closing job
payment/   payments, mock provider, webhook handling
common/    exceptions + error handling, idempotency, config, utilities
```

Inside each feature:

- `api/` – controllers, request/response DTOs (never expose entities)
- `application/` – services; **the only layer that declares `@Transactional`**
- `domain/` – JPA entities, enums, domain rules
- `infrastructure/` – repositories, Redis adapters, external clients

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
