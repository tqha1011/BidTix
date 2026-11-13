# ADR-0002: Take payment outside the database transaction and issue tickets after it

## Status
Accepted (interim: the payment provider is a mock)

## Context
Two rules from `CLAUDE.md` and the schema shape the booking flow:

- No external I/O (payment provider, HTTP) inside a database transaction. A slow provider would keep a
  connection and row locks open and exhaust the pool.
- A ticket is issued only after a successful payment (`tickets` is documented that way, and its
  `public_id` is the QR payload).

The real payment provider is not integrated yet, but the booking code must not need a rewrite when it is.

## Decision
`BookingService.bookTicket` is **not** `@Transactional`. It runs three steps:

1. **Reserve** (transaction 1, `reserve`): validate, create the order as `PENDING_PAYMENT` and hold the
   seats (see ADR-0001). It returns a `Reservation` holding the order id and the pending result.
2. **Charge** (no transaction open): `PaymentGateway.charge(orderCode, amount)`.
3. **Complete** (transaction 2, `completePayment`), only if the charge succeeded:
   - check the order is still `PENDING_PAYMENT` and mark it `PAID` (guarded by `@Version`);
   - move its seats `HELD` to `SOLD` with a conditional UPDATE and compare the row count with the
     number of seated items, so a seat whose hold was lost makes the step fail instead of selling it;
   - issue the tickets.

If the charge returns `false`, the method returns the pending result (status `PENDING_PAYMENT`, no
tickets, `expiresAt` set). The order and its seat holds stay until they expire.

The two transactions use `TransactionTemplate` inside the service rather than two `@Transactional`
methods. Calling a `@Transactional` method of the same bean from another method skips the proxy, so the
annotation would silently do nothing.

`PaymentGateway` is an interface in `payment.service`; `MockPaymentGateway` (`payment.infrastructure`)
always returns `true`. Booking depends on the interface only, so a real provider replaces the mock
without touching the booking flow.

`BookingTicketSeatsResult` describes one booking (order status, total, currency, `expiresAt`, `paidAt`) with a
list of issued tickets, which is empty until the order is paid.

### Alternatives considered
| Option | Why not |
|---|---|
| One `@Transactional` method with the payment call inside | Breaks the no-I/O-in-transaction rule. With a real provider, every slow call holds a connection and the seat row locks. |
| Issue tickets at hold time, cancel them if payment fails | Contradicts "issued only after payment", puts unpaid tickets (with a QR payload) in circulation, and needs a cancel path. |
| Two beans (`BookingService` calls `OrderFulfilmentService`) | Also correct. Rejected for now to keep the flow readable in one class; reconsider if the class grows. |

## Consequences
**Gains**
- The payment call never holds a database connection or a lock.
- The seam for the real provider is in place: swap the `PaymentGateway` implementation.
- Tickets exist only for paid orders.

**Costs and open points**
- **Charged but no tickets.** If the charge succeeds and transaction 2 fails (for example the hold expired
  meanwhile), the customer has paid for nothing. With the mock this cannot happen; with a real gateway this
  branch must trigger a refund. There is a `TODO(payment)` in `bookTicket`.
- **Synchronous shape is temporary.** Real providers confirm asynchronously through a webhook. Then step 3
  becomes the webhook handler, deduplicated by `(provider, provider_event_id)` in `payment_webhook_events`,
  and `bookTicket` ends after step 1 by returning the `PENDING_PAYMENT` result. `completePayment` is written
  so that it can be called from there.
- **Failed payments leave held seats** until the (not yet built) expiry sweeper releases them.
- `POST /orders` will need the `Idempotency-Key` handling from `CLAUDE.md`: a retry after step 2 must not
  charge twice.
- The failure paths (charge returns `false`, hold expired before completion) have no automated test yet.
