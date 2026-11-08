-- Module: payment

CREATE TABLE payments
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id       UUID        NOT NULL DEFAULT gen_random_uuid(),
    order_id        BIGINT      NOT NULL,
    provider        VARCHAR(20) NOT NULL,
    provider_txn_id VARCHAR(100), -- assigned once the provider accepts the payment request
    amount          BIGINT      NOT NULL, -- VND, must equal orders.total_amount
    currency        VARCHAR(3)  NOT NULL DEFAULT 'VND',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    failure_reason  VARCHAR(255),
    raw_response    JSONB,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_payments_public_id UNIQUE (public_id),
    CONSTRAINT uq_payments_provider_txn UNIQUE (provider, provider_txn_id),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_payments_provider CHECK (provider IN ('MOCK')),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REFUNDED'))
);

CREATE INDEX idx_payments_order_id ON payments (order_id);

-- Inbox for provider webhooks: a duplicate delivery violates the unique constraint and is skipped.
CREATE TABLE payment_webhook_events
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider          VARCHAR(20)  NOT NULL,
    provider_event_id VARCHAR(100) NOT NULL,
    payload           JSONB        NOT NULL,
    processed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_payment_webhook_events_provider_event UNIQUE (provider, provider_event_id)
);
