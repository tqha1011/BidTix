<p align="center">
  <img src="docs/images/logo.svg" alt="BidTix: a ticket with rising bars and a check mark" width="420">
</p>

<p align="center">
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

> **Status: work in progress.** The foundation (database schema, entities, authentication, error handling,
> Redis cache and rate limiting) and the booking flow are done and tested against a real PostgreSQL and Redis:
> numbered seats (hold, mock payment, tickets) and standing zones (a counter decrement), a Redis gate that
> refuses known sold-out requests early, and a job that returns the stock of unpaid orders. Organizers can
> create and publish events, and logged-in users can browse them. The booking flow has no HTTP endpoint yet,
> and auctions (only the entities exist), the idempotency filter and the real payment provider are not
> implemented. See [Roadmap](#roadmap) for exactly what exists.

## What it demonstrates

| Problem | Approach | State |
|---|---|---|
| Two people buy the last seat | Conditional `UPDATE ... WHERE status = 'AVAILABLE'` + affected-row check; partial unique index on active tickets as the last line of defence ([ADR-0001](docs/ADR/0001-hold-seats-with-a-conditional-update.md)) | **Done for seated zones** (30 parallel users, 1 seat: 1 wins; 60 users on 10 seats: no seat sold twice, no deadlock) |
| Payment call must not hold the database | Reserve in one transaction, charge with no transaction open, then complete in a second one ([ADR-0002](docs/ADR/0002-take-payment-outside-the-transaction.md)) | Done with a mock provider |
| Overselling standing tickets | Atomic `UPDATE ... SET available = available - n WHERE available >= n` + `CHECK (available BETWEEN 0 AND capacity)` | Done: `UPDATE ... WHERE available_quantity >= n` + row count, concurrency test ([ADR-0003](docs/ADR/0003-take-general-admission-stock-with-a-conditional-update.md)) |
| Concurrent bids | One conditional `UPDATE` on the auction row (price, bid count, anti-sniping extension), `UNIQUE (auction_id, amount)` | Schema done, flow planned |
| Client retries a request | `Idempotency-Key` header, request hash, replayed response | Table done, flow planned |
| Stolen refresh token | Rotation + reuse detection; parallel use of one token decided by a conditional `UPDATE` (verified with 10 simultaneous requests: 1 wins, 9 rejected) | **Done** |
| Consistent error responses | RFC 9457 `ProblemDetail` + stable error codes, also for 401/403 raised inside security filters | **Done** |
| Most requests lose once an event sells out | A short-lived Redis flag refuses requests already known to fail, before any transaction opens. It can only refuse: a missing flag or Redis down lets the request through and the conditional `UPDATE` decides ([ADR-0004](docs/ADR/0004-refuse-known-sold-out-requests-with-a-redis-flag.md)) | **Done**, measured in [docs/benchmarks.md](docs/benchmarks.md) |
| Unpaid orders keep stock locked, across several instances | `@Scheduled` job: batches of expired orders locked with `SELECT ... FOR UPDATE SKIP LOCKED`, stock returned in the same transaction, gate flags cleared after the commit | **Done for booking orders**; auction closing and offer expiry planned |
| Hot reads | Redis cache-aside with explicit TTLs, evicted on write; locking cache writer so 16 simultaneous misses cause one database load | **Done** (venue, published event list) |
| Abuse and floods | Redis rate limits: fixed window per IP for login and register, token bucket per logged-in user; fail-closed for auth | **Done** |

## Architecture

BidTix is a **modular monolith**: one deployable Spring Boot application and one PostgreSQL database,
split into business modules by feature (package-by-feature). Inside each module the code is a classic
**layered architecture**.

```
                      one Spring Boot application, one PostgreSQL database
 ┌──────────────────────────────────────────────────────────────────────────────────┐
 │  auth     user     event     booking     auction     payment     │   common      │
 │  (done)   (entity) (create,  (seats + GA (entity)    (mock)      │   config      │
 │                    publish)  flow, gate,                         │  (shared)     │
 │                              cancel job)                         │               │
 │   each module:   controller ──► service ──► repository ──► DB                    │
 │                  (HTTP, DTOs)  (@Transactional, rules)   (Spring Data)           │
 │                                    │                                             │
 │                                    └──► domain (JPA entities, enums)             │
 └──────────────────────────────────────────────────────────────────────────────────┘
        PostgreSQL = source of truth.  Redis = cache, booking gate, rate limits only.

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
Flyway · Redis (cache, booking gate, rate limiting) · Lombok · springdoc-openapi · JUnit 5, Mockito, MockMvc,
Testcontainers (PostgreSQL and Redis).

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

### Try the auth API

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

| Endpoint | Result |
|---|---|
| `POST /api/v1/auth/register` | `201`, creates a `CUSTOMER` (the role cannot be chosen by the client) |
| `POST /api/v1/auth/login` | `200`, access JWT (RS256, 5 min) in the body, refresh token in an `HttpOnly; SameSite=Strict` cookie |
| `POST /api/v1/auth/refresh` | `200`, new access token and a rotated refresh cookie; `401` if missing, expired or already used |
| `POST /api/v1/auth/logout` | `204`, always, and the cookie is cleared |

**Sample data (dev only):** `make run-seed` starts the app and, on an empty database, creates `admin@bidtix.dev`,
`organizer@bidtix.dev` and `customer1..3@bidtix.dev` (all with password `Password123!`), two venues, two `DRAFT`
events with seated and standing zones, and six paid tickets. Running it again does nothing. Never enable it in production.

Management endpoints (Bearer token with the right role, otherwise `403` with `code: FORBIDDEN`):

| Endpoint | Role | Result |
|---|---|---|
| `POST /api/v1/venues` | ADMIN | `201`, a venue with its `publicId` |
| `POST /api/v1/events` | ORGANIZER | `201`, a `DRAFT` event owned by the caller |
| `POST /api/v1/events/{eventPublicId}/zones` | ORGANIZER (owner) | `201`, a zone; SEATED zones get all seats from `rows` (`A`: 1–20, ...) in one transaction; `409 ZONE_NAME_TAKEN` on a duplicate name |
| `POST /api/v1/events/{eventPublicId}/publish` | ORGANIZER (owner) | `200`, `DRAFT` to `PUBLISHED`; needs at least one zone; evicts the cached event pages |
| `GET /api/v1/admin/events/{eventPublicId}/tickets?status=&page=&size=` | ADMIN | `200`, paginated issued tickets (size capped at 100) |

Read endpoints for any logged-in user (cached in Redis with a short TTL):

| Endpoint | Result |
|---|---|
| `GET /api/v1/venues/{venuePublicId}` | `200`, one venue |
| `GET /api/v1/events?page=&size=` | `200`, published events that have not started yet, soonest first |

Rate limits: login 5 per minute and register 3 per minute (per IP, `429 TOO_MANY_REQUESTS` with `Retry-After`);
other logged-in calls share a bucket of 15 requests refilled by 3 every 10 s. Details in `bidtix.rate-limit.policies`.

Errors look like this, and clients should branch on `code`:

```json
{ "status": 409, "title": "Conflict", "detail": "Email is already registered", "code": "EMAIL_ALREADY_EXISTS" }
```

## Tests

```bash
./mvnw test                                  # everything (Docker must be running)
./mvnw test -Dtest=AuthServiceTest           # one class
./mvnw test -Dtest=BookingConcurrencyTest    # the booking concurrency tests
```

Unit tests cover the auth service (rotation, reuse detection, the parallel-refresh race, locked users),
the controllers (cookie flags, status codes), JWT validation (issuer, audience, expiry) and the error
format for 401/403 (including extra fields such as `seatPublicIds`).

Integration tests extend `PostgresIntegrationTest` and run the whole application against a real PostgreSQL
started by Testcontainers, with Flyway building the schema. The context smoke test
(`BidtixBackendApplicationTests`) uses it too, so it checks the migrations and all JPA queries.
`BookingConcurrencyTest` fires parallel bookings and asserts the invariants:

- 30 users race for one seat: exactly one wins, the others get `SEAT_UNAVAILABLE` and leave no order.
- 60 users ask for 2-3 random seats out of 10: no deadlock, no seat sold twice, none left `HELD`, and the
  database agrees with the responses.
- Standing zone: 30 users race for the last ticket (exactly one wins), and 60 users ask for 1-5 tickets each
  out of 20: the counter never goes negative, never loses an update and equals capacity minus tickets sold.

Other integration tests cover the Redis booking gate (including Redis being down: the flow still sells correctly),
the unpaid-order cancellation job, the published event list and the rate limiter.

These tests check correctness, not throughput or latency, and they do not cover failed payments yet. Throughput
is measured separately and is tagged `benchmark`, so `make test` skips it:

```bash
make bench          # five anti-overselling strategies compared
make bench-gate     # the booking gate on the real booking flow, on and off
make bench-cancel   # cancelling unpaid orders, 1 vs many orders per transaction
```

Method, numbers and caveats: [`docs/benchmarks.md`](docs/benchmarks.md).

## Documentation

- [`docs/database-schema.md`](docs/database-schema.md): tables, constraints, and the concurrency strategy
- [`docs/ADR/`](docs/ADR): architecture decision records
  - [ADR-0001](docs/ADR/0001-hold-seats-with-a-conditional-update.md): hold seats with a conditional UPDATE
  - [ADR-0002](docs/ADR/0002-take-payment-outside-the-transaction.md): take payment outside the transaction, issue tickets after it
  - [ADR-0003](docs/ADR/0003-take-general-admission-stock-with-a-conditional-update.md): take standing-zone stock with a conditional UPDATE
  - [ADR-0004](docs/ADR/0004-refuse-known-sold-out-requests-with-a-redis-flag.md): refuse known sold-out requests with a short-lived Redis flag
- [`docs/benchmarks.md`](docs/benchmarks.md): anti-overselling strategies, the booking gate and order cancellation, measured
- [`CLAUDE.md`](CLAUDE.md): conventions and rules for working in this repository

## Roadmap

- [x] Database schema (Flyway migrations V1–V14 by module) and JPA entities
- [x] Authentication: register, login, refresh with rotation, logout, refresh token in a cookie
- [x] Global error handling
- [x] Testcontainers (Postgres) and a working context test
- [x] Booking service for seated zones: seat holds, mock payment, tickets issued after payment (no HTTP endpoint yet)
- [x] Concurrency tests for seat booking (N parallel requests, assert invariants)
- [x] ADRs for the seat hold and the payment boundary
- [x] Venue / event / zone+seats creation and the admin ticket list, with role checks
- [x] Event publish and browse API (cached)
- [ ] Booking endpoint (`POST /orders`)
- [x] Booking of standing zones: counter decrement, 5 tickets per order, concurrency tests
- [x] Booking: unpaid-order cancellation job (seats and standing quantity returned, `SKIP LOCKED`)
- [ ] Booking: per-user ticket limit
- [x] Benchmarks of five anti-overselling strategies, the booking gate and order cancellation, [docs/benchmarks.md](docs/benchmarks.md)
- [ ] Reproduce overselling with a naive flow, then fix it (ADR)
- [x] Redis: cache-aside, rate limiting, booking gate ([ADR-0004](docs/ADR/0004-refuse-known-sold-out-requests-with-a-redis-flag.md))
- [ ] Idempotency-Key filter
- [ ] Real payment provider: idempotent webhooks, refund when a paid order cannot be fulfilled
- [ ] Auctions: bidding, anti-sniping, closing job, second-chance offers (entities exist)
- [ ] Event cancel and update (must evict the event cache and clear the gate flags)
- [ ] Concurrency tests for the remaining flows (bids, payment failures)
- [ ] Observability: expose a secured `/actuator/prometheus` (only `health` is exposed today)

## License

MIT, see [`LICENSE`](LICENSE).
