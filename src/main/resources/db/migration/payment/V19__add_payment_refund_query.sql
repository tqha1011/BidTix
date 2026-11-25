-- Module: payment
-- Asking the provider where a requested refund stands. refund_queries counts the scheduled queries (they stop at a
-- limit, or at once when the refund was rejected), refund_next_query_at spaces them out.
ALTER TABLE payments
    ADD COLUMN refund_queries INTEGER NOT NULL DEFAULT 0;

ALTER TABLE payments
    ADD COLUMN refund_next_query_at TIMESTAMPTZ;

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_refund_queries CHECK (refund_queries >= 0);
