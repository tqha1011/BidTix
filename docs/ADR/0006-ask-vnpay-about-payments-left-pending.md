# ADR-0006: Ask VNPAY about payments left PENDING

## Status
Accepted. Amends [ADR-0005](0005-confirm-vnpay-payments-in-the-ipn-callback.md): the callback is no longer the only thing
that confirms a payment, and the grace period is 15 minutes.

## Context
A payment left `PENDING` only when VNPAY's callback said so. When the callback never arrived, or was never processed, a
customer could pay and get neither a ticket nor a refund, and nothing showed which payment it was. A customer who paid
must get the ticket.

## Decision
`PaymentPendingCancelJob`, every minute, asks VNPAY (querydr) about `PENDING` payments.

- **When:** 3, 10, 17 and 24 minutes after the payment was made, then after 1 and 24 hours; 6 queries at most. The page
  closes 1 to 15 minutes after the payment is made, and the stock stays 15 minutes more, so at least two queries fall in
  between. Changing `expire-minutes` or `confirmation-grace` means revisiting these times.
- **What decides:** a signed answer about this reference, response code `00`, a payment record (type `01`) and then
  transaction status `00` with our amount (the payment is applied as a successful callback would be) or `02` (`FAILED`).
  Everything else leaves the payment `PENDING`, with one exception below.
- **Giving up:** at the last query, a day after the payment was made, an answer that shows no payment (status `01`, or
  response code `91`, which is taken even unsigned, as error answers come) makes the payment `CANCELLED`. An error, a
  missing answer or a status that suggests money moved (`04`, `07`) does not: that payment stays `PENDING` for a person.
  Not `FAILED`: nothing was reported as failed. Not earlier: a payment that leaves `PENDING` is not asked about again.
- **Same guard as the callback:** the payment leaves `PENDING` through one conditional `UPDATE` whose row count is
  checked. Taking a payment to query it does not change its `version`, so a callback already at work cannot lose to it.
- **A late success counts.** A successful callback is also applied to a `FAILED` or `CANCELLED` payment: the order takes
  the money (tickets) if it still can, otherwise `REFUND_PENDING`. A late failure changes nothing, and `SUCCEEDED`,
  `REFUND_PENDING` and `REFUNDED` are never changed. The conditional `UPDATE` names the status that was read.
- **Any other callback after the decision** is answered `02`; when it says the same as what was recorded, its event is
  marked processed.
- **No call inside a transaction.** A payment is taken (`FOR UPDATE SKIP LOCKED`, counted, next time planned) and
  committed, then VNPAY is called. A run asks about 20 payments at most and stops at the first call that gets no answer,
  because the scheduled jobs share one thread.

### Alternatives considered
| Option | Why not |
|---|---|
| Query only after the order is cancelled | The stock is gone: a customer who paid could only be refunded. |
| Hold the stock until VNPAY answers | While querydr fails, abandoned orders would lock seats for good. |
| Confirm from the return URL | Rejected in ADR-0005; it passes through the customer's browser. |

## Consequences
**Gains**
- A paid order gets its tickets without a callback, as long as VNPAY answers within the grace period; money found later
  is marked `REFUND_PENDING`.

**Costs and open points**
- The meanings of the answer codes come from VNPAY's documentation. **No real answer about a payment has been seen**; the
  sandbox has answered querydr with `99`.
- An abandoned page keeps its stock 15 minutes after the deadline (10 before).
- A payment still `PENDING` after the last query (no usable answer) stays so, and has no gauge of its own.
- A slow VNPAY can still delay the other scheduled jobs by one call's timeout in each run; a larger scheduler pool
  (`spring.task.scheduling.pool.size`) would remove that.
