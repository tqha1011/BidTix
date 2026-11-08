-- Module: booking (orders, order items, tickets)

CREATE TABLE orders
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id    UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id      BIGINT      NOT NULL,
    event_id     BIGINT      NOT NULL,
    source       VARCHAR(20) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING_PAYMENT',
    total_amount BIGINT      NOT NULL, -- VND
    currency     VARCHAR(3)  NOT NULL DEFAULT 'VND',
    expires_at   TIMESTAMPTZ NOT NULL, -- payment deadline
    paid_at      TIMESTAMPTZ,
    version      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_orders_public_id UNIQUE (public_id),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_orders_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT ck_orders_source CHECK (source IN ('BOOKING', 'AUCTION')),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT ck_orders_total_amount CHECK (total_amount >= 0),
    CONSTRAINT ck_orders_paid_at CHECK (status NOT IN ('PAID', 'REFUNDED') OR paid_at IS NOT NULL)
);

CREATE INDEX idx_orders_user_id ON orders (user_id);
CREATE INDEX idx_orders_event_id ON orders (event_id);

CREATE TABLE order_items
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   BIGINT      NOT NULL,
    zone_id    BIGINT      NOT NULL,
    seat_id    BIGINT, -- SEATED zones only
    quantity   INT         NOT NULL,
    unit_price BIGINT      NOT NULL, -- price snapshot at order time
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_items_zone FOREIGN KEY (zone_id) REFERENCES ticket_zones (id),
    CONSTRAINT fk_order_items_seat FOREIGN KEY (seat_id) REFERENCES seats (id),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_seat_quantity CHECK (seat_id IS NULL OR quantity = 1),
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
CREATE INDEX idx_order_items_zone_id ON order_items (zone_id);
CREATE INDEX idx_order_items_seat_id ON order_items (seat_id);

-- Issued only after a successful payment. public_id doubles as the QR payload.
CREATE TABLE tickets
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id     UUID        NOT NULL DEFAULT gen_random_uuid(),
    order_id      BIGINT      NOT NULL,
    order_item_id BIGINT      NOT NULL,
    event_id      BIGINT      NOT NULL,
    zone_id       BIGINT      NOT NULL,
    seat_id       BIGINT, -- NULL for GENERAL_ADMISSION
    owner_id      BIGINT      NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'VALID',
    used_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_tickets_public_id UNIQUE (public_id),
    CONSTRAINT fk_tickets_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_tickets_order_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_tickets_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_tickets_zone FOREIGN KEY (zone_id) REFERENCES ticket_zones (id),
    CONSTRAINT fk_tickets_seat FOREIGN KEY (seat_id) REFERENCES seats (id),
    CONSTRAINT fk_tickets_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT ck_tickets_status CHECK (status IN ('VALID', 'USED', 'CANCELLED')),
    CONSTRAINT ck_tickets_used_at CHECK ((status = 'USED') = (used_at IS NOT NULL))
);

CREATE INDEX idx_tickets_order_id ON tickets (order_id);
CREATE INDEX idx_tickets_order_item_id ON tickets (order_item_id);
CREATE INDEX idx_tickets_event_id ON tickets (event_id);
CREATE INDEX idx_tickets_zone_id ON tickets (zone_id);
CREATE INDEX idx_tickets_seat_id ON tickets (seat_id);
CREATE INDEX idx_tickets_owner_id ON tickets (owner_id);

-- seats.held_by_order_id -> orders (orders did not exist yet in the event migration)
ALTER TABLE seats
    ADD CONSTRAINT fk_seats_held_by_order FOREIGN KEY (held_by_order_id) REFERENCES orders (id);

CREATE INDEX idx_seats_held_by_order_id ON seats (held_by_order_id);
