# ADR-0001: Hold seats with a conditional UPDATE

## Status
Accepted

## Context
Many customers can try to buy the same numbered seats at the same moment. The seat check
(`checkAvailableSeats`) is a plain read, so between that read and the write another request can take
the seat (check-then-act race). The platform must never sell a seat twice, must not deadlock when
requests ask for overlapping sets of seats, and must not leave half a booking behind when one of the
seats is lost.

PostgreSQL is the source of truth (see `CLAUDE.md`); Redis may only be a fast-reject gate, never the
only record of a hold.

## Decision
Holding seats is a **single conditional UPDATE**, and its affected row count decides the outcome:

```sql
UPDATE seats
SET status = 'HELD', held_by_order_id = :orderId, hold_expires_at = :expiresAt, version = version + 1
WHERE id IN (:seatIds) AND status = 'AVAILABLE' AND held_by_order_id IS NULL
```

(`SeatRepository.updateSeatHoldBySeatIds`.) If the row count differs from the number of requested
seats, the service throws `SEAT_UNAVAILABLE` and the whole transaction rolls back, order included.

How the pieces fit together (`BookingService.reserveSeats`):

- **The read check is only for friendly errors.** `checkAvailableSeats` rejects requests for seats that
  are visibly taken and lists them, but it is never trusted: only the UPDATE decides.
- **The order is inserted first** (`saveAndFlush`), because `seats.held_by_order_id` references it and
  needs its id.
- **Seat ids are sorted ascending** before the UPDATE, so concurrent requests with overlapping seats
  take row locks in the same order.
- **The error names the seats that were lost.** After a short row count, a scalar query
  (`findPublicIdsHeldByOrderId`) asks the database which seats this order really holds. A scalar
  query goes to the database and sees the UPDATE, whereas the seat entities in the persistence
  context are stale after a bulk update. The response carries `seatPublicIds` so the client can
  mark those seats.
- **`hold_expires_at` is part of the same UPDATE** because `ck_seats_hold_consistency` requires every
  `HELD` seat to have both an owner and an expiry.
- **Database constraints stay as the last line of defence**: `ck_seats_hold_consistency` and the
  partial unique index `ux_tickets_active_seat` (one non-cancelled ticket per seat).

### Alternatives considered
| Option | Why not |
|---|---|
| `SELECT ... FOR UPDATE`, then UPDATE | Two round trips, and the lock is held while the application runs. The conditional UPDATE takes the lock only for the write. |
| `@Version` on `Seat` (optimistic locking) | A booking of several seats would fail with an exception for each lost seat and need retry logic. The row count gives one clear answer. `version` is still bumped so other optimistic writers notice the change. |
| Redis lock per seat | Redis cannot be the only record of a hold, and the database check would still be needed. It may be added later as a fast-reject gate in front of this. |
| `SERIALIZABLE` isolation | Pushes retry handling on every write path for a problem that one conditional UPDATE already solves. |

## Consequences
**Gains**
- A seat can be held by one order only, whatever the interleaving. This is checked by
  `BookingConcurrencyTest` against a real PostgreSQL (Testcontainers):
  - 30 users racing for one seat: exactly one wins, 29 get `SEAT_UNAVAILABLE`, and the database has one
    ticket, one `SOLD` seat and one order.
  - 60 users asking for 2-3 random seats out of 10, in random order: no deadlock, no failure other than
    `SEAT_UNAVAILABLE`, no seat sold twice, no seat left `HELD`, and the database matches the responses.
- The tests do detect a broken guard: with the `status = 'AVAILABLE'` condition removed from the UPDATE,
  both tests fail.

**Costs and caveats**
- A request that loses the race has already inserted its order; the rollback discards it. That is wasted
  work, but it leaves no data behind (the test asserts this).
- The UPDATE is a bulk update, so it bypasses the persistence context. Seat entities loaded before it are
  stale and must not be read afterwards for their status.
- Nothing is fair: whichever transaction reaches the row first wins.

**Not covered yet**
- Nothing releases an expired hold yet. The sweeper (`FOR UPDATE SKIP LOCKED`, planned) must settle its
  race with the payment step through a conditional update on the order status.
- General admission zones use a different mechanism, a counter decremented atomically; see
  [ADR-0003](0003-take-general-admission-stock-with-a-conditional-update.md).
- Tests check correctness, not throughput or latency.
- The README roadmap item "reproduce overselling with a naive flow" has not been done as a separate
  test. The guard-removed run above is the only evidence of what happens without the conditional UPDATE.
