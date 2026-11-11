# BidTix

A concert ticketing backend with two ways to buy a ticket: **fixed-price booking** (numbered seats and
standing zones) and **English auctions** for premium tickets.

The goal is to get the hard backend problems right rather than to build a big feature list:
no overselling, no double-sold seats, no lost bids, safe client retries, short and correct transactions,
and caching that never decides who gets a ticket.

> **Status: work in progress.** The foundation (database schema, entities, authentication, error handling) is
> done and tested. The booking, auction and payment flows are designed but not implemented yet. See
> [Roadmap](#roadmap) for exactly what exists.

## What it demonstrates

| Problem | Approach | State |
|---|---|---|
| Two people buy the last seat | Conditional `UPDATE ... WHERE status = 'AVAILABLE'` + affected-row check; partial unique index on active tickets as the last line of defence | Schema done, flow planned |
| Overselling standing tickets | Atomic `UPDATE ... SET available = available - n WHERE available >= n` + `CHECK (available BETWEEN 0 AND capacity)` | Schema done, flow planned |
| Concurrent bids | One conditional `UPDATE` on the auction row (price, bid count, anti-sniping extension), `UNIQUE (auction_id, amount)` | Schema done, flow planned |
| Client retries a request | `Idempotency-Key` header, request hash, replayed response | Table done, flow planned |
| Stolen refresh token | Rotation + reuse detection; parallel use of one token decided by a conditional `UPDATE` (verified with 10 simultaneous requests: 1 wins, 9 rejected) | **Done** |
| Consistent error responses | RFC 9457 `ProblemDetail` + stable error codes, also for 401/403 raised inside security filters | **Done** |
| Expiring holds and auctions across several instances | `@Scheduled` jobs using `SELECT ... FOR UPDATE SKIP LOCKED` | Planned |
| Hot reads | Redis cache-aside with short TTLs; the database always validates writes | Planned |

## Architecture

BidTix is a **modular monolith**: one deployable Spring Boot application and one PostgreSQL database,
split into business modules by feature (package-by-feature). Inside each module the code is a classic
**layered architecture**.

```
                      one Spring Boot application, one PostgreSQL database
 ┌──────────────────────────────────────────────────────────────────────────────────┐
 │  auth     user     event     booking     auction     payment     │   common      │
 │  (done)   (entity) (entity)  (entity)    (entity)    (entity)    │   config      │
 │                                                                  │  (shared)     │
 │   each module:   controller ──► service ──► repository ──► DB                    │
 │                  (HTTP, DTOs)  (@Transactional, rules)   (Spring Data)           │
 │                                    │                                             │
 │                                    └──► domain (JPA entities, enums)             │
 └──────────────────────────────────────────────────────────────────────────────────┘

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
Flyway · Lombok · springdoc-openapi · JUnit 5, Mockito, MockMvc.
Planned: Redis, Testcontainers.

## Getting started

Requirements: JDK 21 and Docker (Spring Boot starts PostgreSQL from `compose.yaml` automatically).

```bash
# 1. Create the JWT signing keys (git-ignored, needed once)
mkdir -p keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/private.pem
openssl pkey -in keys/private.pem -pubout -out keys/public.pem

# 2. Run (the refresh cookie is "Secure"; switch that off only for plain-http local testing)
BIDTIX_AUTH_REFRESHCOOKIE_SECURE=false ./mvnw spring-boot:run
```

Shortcut for both steps: `make run-local`. Run `make help` to see all commands (`make test`,
`make test-one T=AuthServiceTest`, `make psql`, ...).

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

Errors look like this, and clients should branch on `code`:

```json
{ "status": 409, "title": "Conflict", "detail": "Email is already registered", "code": "EMAIL_ALREADY_EXISTS" }
```

## Tests

```bash
./mvnw test                          # everything
./mvnw test -Dtest=AuthServiceTest   # one class
```

Unit tests cover the auth service (rotation, reuse detection, the parallel-refresh race, locked users),
the controllers (cookie flags, status codes), JWT validation (issuer, audience, expiry) and the error
format for 401/403 raised in the security filters.

The Spring context smoke test (`BidtixBackendApplicationTests`) currently fails because tests have no
database; it will be fixed together with the Testcontainers setup that the concurrency tests need.

## Documentation

- [`docs/database-schema.md`](docs/database-schema.md): tables, constraints, and the concurrency strategy
- [`docs/ADR/`](docs/ADR): architecture decision records
- [`CLAUDE.md`](CLAUDE.md): conventions and rules for working in this repository

## Roadmap

- [x] Database schema (Flyway migrations V1–V12 by module) and JPA entities
- [x] Authentication: register, login, refresh with rotation, logout, refresh token in a cookie
- [x] Global error handling
- [ ] Testcontainers (Postgres) and a working context test
- [ ] Events, zones and seats API
- [ ] Booking: seat holds, standing-zone stock, expiry sweeper, per-user ticket limit
- [ ] Reproduce overselling with a naive flow, then fix it (ADR)
- [ ] Idempotency-Key filter
- [ ] Mock payment provider with idempotent webhooks, ticket issuing
- [ ] Auctions: bidding, anti-sniping, closing job, second-chance offers
- [ ] Redis: cache-aside, rate limiting
- [ ] Concurrency integration tests (N parallel requests, assert invariants)
- [ ] Observability: expose a secured `/actuator/prometheus` (only `health` is exposed today)

## License

MIT, see [`LICENSE`](LICENSE).
