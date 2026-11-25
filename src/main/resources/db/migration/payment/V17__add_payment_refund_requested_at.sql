-- Module: payment
-- When a REFUND_PENDING payment was taken to be refunded. The mark is written (and committed) before the provider is
-- called, because that call happens outside any transaction, where no row lock protects the payment: the mark is what
-- stops a second run, or a second instance, from asking for the same refund again.
ALTER TABLE payments
    ADD COLUMN refund_requested_at TIMESTAMPTZ;
