-- Module: common
-- The cleanup job reads the expired keys every minute, oldest first.
CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);
