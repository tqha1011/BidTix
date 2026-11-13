# ADR-0003: Take standing-ticket stock with a conditional UPDATE on the zone counter

## Status
Accepted

## Context
A `GENERAL_ADMISSION` zone has no numbered seats: its stock is one counter, `ticket_zones.available_quantity`.
Many customers can ask for tickets at the same moment, and the platform must never sell more than the
capacity. Unlike seats, where requests spread over many rows, every buyer of a zone fights for the **same
row**, so the way the counter is changed decides both correctness and throughput.

The same flow rules as for seats apply (ADR-0001, ADR-0002): PostgreSQL is the source of truth, the check
and the change must be one atomic step whose row count is verified, and no payment call runs inside a
transaction.

## Decision
`BookingService.bookTicket` handles both zone types with the same three steps. For a GA zone, step 1
(`reserveQuantity`) takes the stock with **one conditional UPDATE** and checks its row count:

```sql
UPDATE ticket_zones
SET available_quantity = available_quantity - :quantity, version = version + 1
WHERE id = :zoneId AND zone_type = 'GENERAL_ADMISSION' AND available_quantity >= :quantity
```

(`TicketZoneRepository.takeQuantity`.) 1 row = the tickets are ours; 0 rows = sold out or not enough left,
so the service throws `ZONE_SOLD_OUT` (with the current `availableQuantity` as a hint for the client) and
the whole transaction, order included, rolls back.

Details that matter:

- **The UPDATE is the last write of the transaction.** It locks the counter row until commit, and every
  buyer of the zone queues behind it. So everything else is done first: validation, the lazy venue read,
  and inserting the order. Only the commit follows. (Seats insert the order first because
  `seats.held_by_order_id` has a foreign key to it; the counter has none, so GA can take this order.)
- **One order item with `quantity = N`**, price snapshot once. After payment, `completePayment` issues **N
  tickets** pointing at that item, because each person at the door needs their own QR code. Nothing is
  marked `SOLD`: the stock was already subtracted at reserve time.
- **Limit of 5 tickets per order**, for seated and standing alike (`bidtix.booking.max-tickets-per-order`).
  The per-user limit `events.max_tickets_per_user` is not enforced yet.
- **A zone of another event is rejected** (`ZONE_NOTFOUND`). This check lives in the shared `reserve`
  step and also closes the same gap for seated zones.
- **Constraints stay as the last line of defence**: `ck_ticket_zones_available_range`
  (`0 <= available_quantity <= capacity`) turns any bug that would drive the counter negative into an error
  instead of an oversale.

### Alternatives considered
| Option | Why not |
|---|---|
| Read `available_quantity`, compare in Java, write the new value | Check-then-act race and a lost update: two buyers read 1, both write 0, both get a ticket. The CHECK would not even fire. |
| `@Version` on `TicketZone` with retry | Under load every buyer but one fails on the hot row and must retry; throughput collapses, and a request can give up while stock remains. |
| `SELECT ... FOR UPDATE`, then UPDATE | Two round trips with the lock held across application code. The conditional UPDATE locks only for the write. |
| Redis counter as the authority | Redis cannot be the only record of a sale (CLAUDE.md). It may later be a fast-reject gate in front of this UPDATE. |

## Consequences
**Gains**
- The counter can never go below zero and never loses a subtraction, whatever the interleaving. Checked by
  `GeneralAdmissionConcurrencyTest` against a real PostgreSQL:
  - 30 users racing for the last ticket: exactly one wins, 29 get `ZONE_SOLD_OUT`, one ticket and one order.
  - 60 users wanting 1-5 tickets each out of 20: only `ZONE_SOLD_OUT` failures, tickets sold equal
    `capacity - available_quantity`, losers leave no order, and the stock left is smaller than the smallest
    request that failed (nothing is wasted).
- The tests detect a broken guard: with `available_quantity >= :quantity` removed from the UPDATE, the
  concurrency tests and two sequential tests fail (the CHECK constraint surfaces as a database error).

**Costs and caveats**
- A single hot row serialises all buyers of a zone. Throughput is bounded by how fast one transaction can
  insert an order and commit. This is the case to look at when comparing strategies.
- A request that loses has already inserted its order; the rollback discards it.
- The UPDATE is a bulk update, so a `TicketZone` loaded earlier in the transaction is stale afterwards.

**Not covered yet**
- Nothing returns the quantity when a payment fails or an order expires; the stock stays taken, as expired
  seat holds do. The sweeper must restore it in the same transaction as a **conditional**
  `PENDING_PAYMENT -> EXPIRED` update of the order; otherwise it races with `completePayment` and the
  quantity could be returned for an order that was paid in the meantime.
- Still service-only: no controller or `Idempotency-Key` handling for booking.
- Tests check correctness, not throughput or latency.
