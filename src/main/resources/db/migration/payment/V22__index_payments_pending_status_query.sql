-- Module: payment
-- The job that asks the provider about PENDING payments reads them every minute, newest first.
CREATE INDEX idx_payments_pending_requested_at ON payments (requested_at) WHERE status = 'PENDING';
