-- Module: booking
-- The Idempotency-Key of the request that made the order. idempotency_keys is written outside the booking transaction,
-- so it can be lost while the order exists; this is the guard that cannot be: one order per user and key.
ALTER TABLE orders
    ADD COLUMN idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX ux_orders_user_idempotency_key
    ON orders (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
