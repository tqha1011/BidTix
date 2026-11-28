-- Module: payment
-- Asking the provider about a payment that is still PENDING, in case its callback never arrives. status_queries counts
-- the queries made, next_status_query_at spaces them out.
ALTER TABLE payments
    ADD COLUMN status_queries INTEGER NOT NULL DEFAULT 0;

ALTER TABLE payments
    ADD COLUMN next_status_query_at TIMESTAMPTZ;

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_status_queries CHECK (status_queries >= 0);
