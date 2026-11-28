-- Module: payment
-- CANCELLED: the provider still showed no payment at the last query about it, so we stopped waiting.
ALTER TABLE payments
    DROP CONSTRAINT ck_payments_status;
ALTER TABLE payments
    ADD CONSTRAINT ck_payments_status
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REFUND_PENDING', 'REFUNDED'));
