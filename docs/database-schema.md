# BidTix – Database Schema

PostgreSQL. The Flyway migrations are the source of truth for columns and constraints. This document
explains the model, the business rules behind it, and the concurrency strategy.

## Migrations by module

`src/main/resources/db/migration/<module>/V{n}__*.sql`. Flyway scans the subfolders recursively.
Version numbers are global across modules, so a new migration always takes the next free number.

| Version | Module | Tables |
|---|---|---|
| V1 | `user` | `users` |
| V2 | `auth` | `refresh_tokens` |
| V3 | `event` | `venues`, `events`, `ticket_zones`, `seats` |
| V4 | `booking` | `orders`, `order_items`, `tickets` (+ FK `seats.held_by_order_id → orders`) |
| V5 | `payment` | `payments`, `payment_webhook_events` |
| V6 | `auction` | `auctions`, `bids`, `auction_offers` |
| V7 | `common` | `idempotency_keys` |
| V8 | `booking` | partial unique `ux_tickets_active_seat` |
| V9 | `auction` | partial unique `ux_auctions_active_seat`, `ux_auction_offers_one_pending` |

## Conventions

- PK: `id BIGINT GENERATED ALWAYS AS IDENTITY`, internal only and never exposed by the API.
- `public_id UUID NOT NULL DEFAULT gen_random_uuid()` + `UNIQUE` on tables exposed through the API:
  `users`, `events`, `orders`, `tickets`, `payments`, `auctions`, `bids`. A ticket's `public_id` is also
  its QR payload.
- Money is `BIGINT` in VND. Time is `TIMESTAMPTZ`.
- Enums are `VARCHAR` + `CHECK`. Strings use `VARCHAR`, never `CHAR`, so Hibernate `validate` maps them cleanly.
- Mutable aggregates have `version BIGINT` for optimistic locking: `events`, `ticket_zones`, `seats`,
  `orders`, `payments`, `auctions`.
- `created_at` on every table. `updated_at` on mutable tables. Append-only tables (`bids`,
  `order_items`, `refresh_tokens`, `payment_webhook_events`) have no `updated_at`.
- Constraint naming: `pk_`, `fk_<table>_<ref>`, `uq_<table>_<cols>`, `ck_<table>_<rule>`, `idx_<table>_<col>`.
- **Indexes**: currently only on FK columns (Postgres does not create them automatically). An FK is
  considered covered when it is the leading column of a `UNIQUE` constraint. Partial unique indexes enforce
  business invariants (see below). Query/performance indexes are deferred.

## Entity relationships

```
users ──< refresh_tokens
users ──< events >── venues
events ──< ticket_zones ──< seats
users ──< orders ──< order_items >── ticket_zones, seats
orders ──< tickets
orders ──< payments                   payment_webhook_events (standalone inbox)
events / ticket_zones / seats ──< auctions ──< bids
auctions ──< auction_offers >── bids, orders
users ──< idempotency_keys
```

## Business rules encoded in the schema

**event**
- `events`: `ends_at > starts_at`, the sale window must close before the show (`sale_ends_at <= starts_at`).
- `ticket_zones`: `SEATED` or `GENERAL_ADMISSION`. Only GA zones have `available_quantity`, with
  `0 <= available_quantity <= capacity` enforced by a `CHECK`, so a GA zone can never be oversold.
- `seats`: `AVAILABLE` / `HELD` / `SOLD` / `RESERVED_FOR_AUCTION`. A `HELD` seat always has
  `held_by_order_id` + `hold_expires_at`, and any other status has neither (`ck_seats_hold_consistency`).

**booking**
- `orders.source` is `BOOKING` or `AUCTION`. Status: `PENDING_PAYMENT → PAID | EXPIRED | CANCELLED`,
  `PAID → REFUNDED`. `paid_at` is required once paid.
- `order_items.unit_price` is a price snapshot. A seated item always has `quantity = 1`.
- `tickets` exist only after a successful payment. `used_at` is set exactly when the status is `USED` (check-in).

**payment**
- `UNIQUE (provider, provider_txn_id)`: a provider transaction maps to at most one payment.
- `payment_webhook_events` is an inbox. The webhook inserts first, and a duplicate delivery violates
  `UNIQUE (provider, provider_event_id)`, so it is acknowledged with `200` and not processed again.
- A successful payment for an order that is already `EXPIRED` is refunded, not applied, because the
  inventory may belong to someone else now.

**auction**
- One auction sells one ticket: a specific seat, or one GA unit. Its inventory is reserved when the
  auction is created (seat becomes `RESERVED_FOR_AUCTION`, GA `available_quantity - 1`), so booking and
  auction never compete for the same unit.
- `current_price` and `leading_bidder_id` are always both set or both `NULL`. `reserve_price` is a hidden floor.
- `bids` stores only accepted bids. `UNIQUE (auction_id, amount)` means two concurrent bids with the
  same amount cannot both be accepted.
- `auction_offers`: `offer_rank 1` is the winner. If they don't pay before `expires_at`, the next-highest
  *distinct* bidder gets the next rank. `UNIQUE (auction_id, offer_rank)`, and one order per offer.

**common**
- `idempotency_keys`: `UNIQUE (user_id, idem_key)`. Insert as `PROCESSING`. On a conflict:
  - a `COMPLETED` row replays the stored `response_status` / `response_body`,
  - a `PROCESSING` row returns `409`,
  - a different `request_hash` returns `422`.

  The response is written in the same transaction as the business change.

## Concurrency strategy

| Problem | Approach |
|---|---|
| Hold `SEATED` seats | Optional Redis `SET seat:{id} NX PX <ttl>` fast-reject. Then in the DB: `UPDATE seats SET status='HELD', held_by_order_id=?, hold_expires_at=? WHERE id IN (...) AND status='AVAILABLE'`, with ids in ascending order. If affected rows ≠ requested seats, roll back. |
| Buy GA | `UPDATE ticket_zones SET available_quantity = available_quantity - :n WHERE id = :id AND available_quantity >= :n`. 0 rows means sold out. |
| Per-user ticket limit | `pg_advisory_xact_lock(user_id, event_id)` in the order transaction, then count existing tickets + pending items. |
| Hold expiry | `@Scheduled` sweeper: `... WHERE status='PENDING_PAYMENT' AND expires_at < now() FOR UPDATE SKIP LOCKED`. It expires the order and releases seats/quantity. The race with the payment webhook is settled by `UPDATE orders ... WHERE status='PENDING_PAYMENT'`, so only one of them wins. |
| Place bid | A single conditional update on `auctions` (`status='LIVE' AND now() < ends_at AND :amt >= COALESCE(current_price + min_increment, start_price)`) that also bumps `bid_count`, `version` and extends `ends_at` inside the anti-snipe window. On 1 row, insert into `bids` in the same transaction. |
| Close auction / offers | Jobs with `FOR UPDATE SKIP LOCKED`. When a `LIVE` auction passes `ends_at` with the reserve met, it moves to `AWAITING_PAYMENT` with a rank-1 offer and an `AUCTION` order. Without bids it `FAILED` and the inventory is released. An expired offer passes to the next rank; when no bidder is left, the auction `FAILED`. |
| Cache | Redis cache-aside for event list/detail, zone availability (short TTL, read hint only), and auction current price. The DB always validates writes. |

## Partial unique indexes (business invariants)

These enforce correctness, not performance:

| Index | Invariant | Why partial |
|---|---|---|
| `ux_tickets_active_seat` on `tickets(seat_id) WHERE seat_id IS NOT NULL AND status <> 'CANCELLED'` | A seat never has two live tickets. This is the final guard against double-selling, behind the conditional `UPDATE seats`. | A seat whose ticket was cancelled/refunded can be sold again |
| `ux_auctions_active_seat` on `auctions(seat_id) WHERE status IN ('SCHEDULED','LIVE','AWAITING_PAYMENT')` | A seat is in at most one active auction | A seat can be re-auctioned after `FAILED`/`CANCELLED` |
| `ux_auction_offers_one_pending` on `auction_offers(auction_id) WHERE status = 'PENDING'` | Only one bidder holds the right to buy at a time | Expired offers stay as history |

## Deferred: query/performance indexes

Add these once queries exist and are measured with `EXPLAIN`:
- `orders(status, expires_at)` for the sweeper, `orders(user_id, created_at DESC)` for history
- `auctions(status, ends_at)` for the closing job, `bids(auction_id, amount DESC)` for top bids
- `events(status, starts_at)` for listing, `seats(zone_id, status)` for the seat map
- `idempotency_keys(expires_at)` and `refresh_tokens(expires_at)` for cleanup
