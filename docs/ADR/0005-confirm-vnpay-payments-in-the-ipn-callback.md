# ADR-0005: Confirm VNPAY payments in the IPN callback, guarded by the payment's status

## Status
Accepted. Supersedes [ADR-0002](0002-take-payment-outside-the-transaction.md). Amended by
[ADR-0006](0006-ask-vnpay-about-payments-left-pending.md): a query also confirms payments, and the grace period is 15
minutes.

## Context
ADR-0002 charged a mock provider synchronously inside the booking request. VNPAY does not work like that: the
customer pays on VNPAY's page, and the result arrives later, in a server-to-server call (the IPN). That changes
what has to be solved:

- **The call is public and repeated.** Anyone can call the route. VNPAY itself sends the same result again until it
  is answered with `00` (recorded) or `02` (already recorded), and deliveries can run in parallel.
- **The money moves before we know.** When the callback arrives the order may already be cancelled, paid by another
  attempt, or, for an auction, offered to the next bidder. The customer has been charged all the same.
- **Processing can fail halfway**, and VNPAY's retry is then the only second chance.
- ADR-0002's rule still holds: no call to a provider inside a database transaction.

## Decision

### Booking and paying are two requests
`POST /api/v1/orders` only reserves the stock (`PENDING_PAYMENT`). `POST /api/v1/orders/{orderCode}/payments`
commits a `PENDING` payment with its own random `txn_ref` and returns the signed VNPAY URL; the page closes no later
than the order's deadline. An order may have several attempts. The browser's return URL is for showing a result
only: it never changes data.

### The IPN is the only thing that confirms a payment
`PaymentService.processVnPayResponse`, in this order:

1. **Signature** (HMAC-SHA512 over all parameters). Wrong: `97`, and nothing is read or written.
2. **Store the callback** in `payment_webhook_events`, in its own transaction:
   `INSERT ... ON CONFLICT (provider, provider_event_id) DO NOTHING`. VNPAY sends no event id, so the id is
   `vnp_TxnRef:vnp_TransactionStatus`.
3. **Process** in one transaction: find the payment (`01` if unknown), compare the amount (`04`), stop if it is not
   `PENDING` (`02`). Then:
   - the transaction failed (`vnp_ResponseCode` and `vnp_TransactionStatus` are not both `00`): payment `FAILED`,
     the order stays payable;
   - it succeeded and `PayableOrders.applyPayment` paid the order (order `PAID`, tickets issued, an auction offer
     settled): payment `SUCCEEDED`;
   - it succeeded but the order cannot take it: payment `REFUND_PENDING`.

   The payment leaves `PENDING` through one conditional `UPDATE ... WHERE status = 'PENDING' AND version = ?`. The
   row count is checked: 0 means another delivery won, so everything this transaction did is rolled back (`02`).
   The same transaction sets the event's `processed_at`, and the answer is `00`.
4. If `applyPayment` **throws** `ORDER_NOT_PAYABLE` (a seat hold was lost, an offer closed), its transaction must
   roll back, so `REFUND_PENDING` is recorded in a second one. This is why the method is not `@Transactional`.
5. Any other error: `99`, no payment or order changed, the stored event stays unprocessed, VNPAY retries.

**The guard against processing twice is the payment's status, not the stored event.** The event table is a record of
what VNPAY said. If "the row exists" meant "already handled", a delivery that was stored and then failed would be
acknowledged on retry, and a paid order would never be applied.

`paid_at` is `vnp_PayDate` (signed by VNPAY), not the time of processing, so a callback that is merely late does not
count against an auction deadline while the offer is still open.

### A grace period before the stock is released
The payment page closes at the order's deadline, and the callback comes after the payment. Releasing the stock at the
deadline would refund customers who paid in time. So the two expiry jobs (`OrderCancellationJob`,
`AuctionOfferExpiryJob`) keep an expired order **that has a `PENDING` payment** for
`bidtix.payment.confirmation-grace` (10 minutes) longer (`ConfirmationGrace`). The customer's deadline does not move:
no payment page is created or stays open past it, and an auction offer is accepted only if `vnp_PayDate` is before the
offer's deadline. An order nobody tried to pay, or whose payments all have a result, is released at its deadline.

A booking order has no such check on `paid_at`: a successful payment is applied whenever the order is still
`PENDING_PAYMENT`.

### Alternatives considered
| Option | Why not |
|---|---|
| Confirm from the return URL | It passes through the customer's browser, which may be closed before it arrives, and the customer controls when and how often it is called. |
| Deduplicate by inserting the event first and skipping a repeat (committed on its own) | A failure after the insert makes every retry a "duplicate": the money is never applied. |
| Insert the event in the processing transaction | Safe against that, but a failed or rejected callback leaves no trace, which is exactly the one to investigate. |
| Close the payment page earlier than the order's deadline | The same gap, but taken from the customer's time to pay. |
| Grace for every expired order | Holds stock longer for orders nobody tried to pay. |
| Revive a cancelled order when its late callback finds the stock still free | A second path into `PAID` with its own races, and no help when the stock was sold again. |
| Call VNPAY's refund API from the callback | Provider I/O inside the request or the transaction; a slow or failing refund would block the confirmation. |
| Answer an unknown payment by refunding it | A valid signature with an unknown reference points at a lost row or another environment. A refund cannot be undone. |

## Consequences
**Gains**
- Parallel and repeated deliveries apply the money once (tested with 8 simultaneous identical callbacks; with the
  row-count check removed the test fails).
- Money taken for an order that cannot be fulfilled is recorded, never silently dropped.
- Every authentic callback can be inspected, including those answered `01`, `04` or `99`.
- `ux_payments_one_succeeded_per_order` backs this up in the database: at most one `SUCCEEDED` payment per order.

**Costs and open points**
- **Refunds are not proven yet.** `RefundPaymentJob` claims `REFUND_PENDING` payments
  (`FOR UPDATE SKIP LOCKED` and `refund_requested_at`, committed) and calls VNPAY outside any transaction. A payment
  becomes `REFUNDED` only when querydr reports a successful full refund, asked right after the request and then on a
  growing schedule, 10 times at most. The VNPAY sandbox has answered refund calls with `500` and querydr with `99`, so
  the reading of a successful answer is untested. Only a request that certainly never reached VNPAY gives up its claim and is retried (1, 5,
  30 minutes, 5 attempts); one that timed out or got an error is not sent again, because it could return the money
  twice. What querydr cannot settle is left to a person.
- **Nothing watches unprocessed events** (`processed_at IS NULL`). After VNPAY's last retry they stay until someone
  looks. A second, different result for an already decided payment (say success after failure) is also only visible
  there: it is answered `02`.
- **A lost callback is never recovered**: there is no query to VNPAY for payments left `PENDING`.
- Stock (or the next bidder) waits up to the grace period longer when a customer opened the payment page, did not
  finish, and no result came back: the payment stays `PENDING`.
- A customer who paid in time can still be refunded when the callback takes longer than the grace period (VNPAY keeps
  retrying for longer than that). Asking VNPAY about `PENDING` payments before releasing the stock would close this.
- The return URL has no endpoint yet.
- Not yet run against the VNPAY sandbox; the tests sign their own callbacks with the project's signer.
