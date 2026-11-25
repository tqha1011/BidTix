-- Module: payment
-- When we asked the provider for the payment: exactly the value sent as VNPAY's vnp_CreateDate, to the second. VNPAY's
-- query and refund APIs need it back (vnp_TransactionDate), and created_at is set separately, so it can differ.
-- Nullable: payments created before this migration never stored the value, and an approximation would be refused.
ALTER TABLE payments
    ADD COLUMN requested_at TIMESTAMPTZ;
