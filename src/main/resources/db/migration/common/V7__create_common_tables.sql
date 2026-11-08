-- Module: common (cross-cutting)

-- Makes retried write requests safe. See docs/database-schema.md for the flow.
CREATE TABLE idempotency_keys
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    idem_key        VARCHAR(64)  NOT NULL,
    request_method  VARCHAR(10)  NOT NULL,
    request_path    VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL, -- SHA-256 hex of the request body
    status          VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING',
    response_status INT,
    response_body   JSONB,
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- also serves as the index for fk_idempotency_keys_user (user_id is the leading column)
    CONSTRAINT uq_idempotency_keys_user_key UNIQUE (user_id, idem_key),
    CONSTRAINT fk_idempotency_keys_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_idempotency_keys_status CHECK (status IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT ck_idempotency_keys_completed CHECK (status <> 'COMPLETED' OR response_status IS NOT NULL)
);
