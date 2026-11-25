-- Module: payment
-- Trying a refund again. A request that certainly never reached the provider gives up its claim
-- (refund_requested_at back to NULL) and waits until refund_next_attempt_at. refund_attempts counts the claims, so the
-- retries end and a person takes over.
ALTER TABLE payments
    ADD COLUMN refund_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE payments
    ADD COLUMN refund_next_attempt_at TIMESTAMPTZ;

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_refund_attempts CHECK (refund_attempts >= 0);
