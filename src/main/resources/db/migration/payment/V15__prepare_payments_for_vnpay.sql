-- Module: payment
-- Prepares `payments` for a real provider (VNPAY), where the customer pays on the provider's page and the result
-- arrives later in a webhook (IPN).

-- The provider list and the status list grow. REFUND_PENDING is a payment the provider confirmed but that cannot be
-- applied (the order expired or was cancelled meanwhile): the money must be given back.
ALTER TABLE payments
    DROP CONSTRAINT ck_payments_provider;
ALTER TABLE payments
    ADD CONSTRAINT ck_payments_provider CHECK (provider IN ('MOCK', 'VNPAY'));

ALTER TABLE payments
    DROP CONSTRAINT ck_payments_status;
ALTER TABLE payments
    ADD CONSTRAINT ck_payments_status
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REFUND_PENDING', 'REFUNDED'));

-- Our reference for one payment attempt (VNPAY's vnp_TxnRef, at most 100 characters). A customer may try again after a
-- failed or abandoned attempt, so an order can have several payments, each with its own reference. The callback names
-- the payment by this value. (provider_txn_id stays the provider's own transaction number, known once it confirms.)
-- Added nullable first so existing rows can be backfilled, then locked down.
ALTER TABLE payments
    ADD COLUMN txn_ref VARCHAR(100);

UPDATE payments
SET txn_ref = 'LEGACY' || id;

ALTER TABLE payments
    ALTER COLUMN txn_ref SET NOT NULL;

ALTER TABLE payments
    ADD CONSTRAINT uq_payments_provider_txn_ref UNIQUE (provider, txn_ref);

-- Last line of defence against taking the money for one order twice: at most one SUCCEEDED payment per order. A second
-- confirmed payment (the customer paid on two pages) must be stored as REFUND_PENDING instead.
CREATE UNIQUE INDEX ux_payments_one_succeeded_per_order ON payments (order_id) WHERE status = 'SUCCEEDED';
