-- Module: event (venues, events, ticket zones, seats)

CREATE TABLE venues
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    address    VARCHAR(500) NOT NULL,
    city       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE events
(
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    organizer_id         BIGINT       NOT NULL,
    venue_id             BIGINT       NOT NULL,
    title                VARCHAR(200) NOT NULL,
    description          TEXT,
    starts_at            TIMESTAMPTZ  NOT NULL,
    ends_at              TIMESTAMPTZ  NOT NULL,
    sale_starts_at       TIMESTAMPTZ  NOT NULL,
    sale_ends_at         TIMESTAMPTZ  NOT NULL,
    max_tickets_per_user INT          NOT NULL DEFAULT 4,
    status               VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_events_public_id UNIQUE (public_id),
    CONSTRAINT fk_events_organizer FOREIGN KEY (organizer_id) REFERENCES users (id),
    CONSTRAINT fk_events_venue FOREIGN KEY (venue_id) REFERENCES venues (id),
    CONSTRAINT ck_events_show_time CHECK (ends_at > starts_at),
    CONSTRAINT ck_events_sale_window CHECK (sale_ends_at > sale_starts_at AND sale_ends_at <= starts_at),
    CONSTRAINT ck_events_max_tickets CHECK (max_tickets_per_user > 0),
    CONSTRAINT ck_events_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED', 'COMPLETED'))
);

CREATE INDEX idx_events_organizer_id ON events (organizer_id);
CREATE INDEX idx_events_venue_id ON events (venue_id);

CREATE TABLE ticket_zones
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id           BIGINT       NOT NULL,
    name               VARCHAR(100) NOT NULL,
    zone_type          VARCHAR(20)  NOT NULL,
    price              BIGINT       NOT NULL, -- VND
    capacity           INT          NOT NULL,
    available_quantity INT,                   -- GENERAL_ADMISSION only; SEATED zones track seats individually
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- also serves as the index for fk_ticket_zones_event (event_id is the leading column)
    CONSTRAINT uq_ticket_zones_event_name UNIQUE (event_id, name),
    CONSTRAINT fk_ticket_zones_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT ck_ticket_zones_type CHECK (zone_type IN ('SEATED', 'GENERAL_ADMISSION')),
    CONSTRAINT ck_ticket_zones_price CHECK (price > 0),
    CONSTRAINT ck_ticket_zones_capacity CHECK (capacity > 0),
    CONSTRAINT ck_ticket_zones_available_by_type
        CHECK ((zone_type = 'GENERAL_ADMISSION') = (available_quantity IS NOT NULL)),
    CONSTRAINT ck_ticket_zones_available_range CHECK (available_quantity BETWEEN 0 AND capacity)
);

CREATE TABLE seats
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    zone_id          BIGINT      NOT NULL,
    row_label        VARCHAR(10) NOT NULL,
    seat_number      INT         NOT NULL,
    status           VARCHAR(30) NOT NULL DEFAULT 'AVAILABLE',
    held_by_order_id BIGINT, -- FK to orders is added in the booking migration
    hold_expires_at  TIMESTAMPTZ,
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- also serves as the index for fk_seats_zone (zone_id is the leading column)
    CONSTRAINT uq_seats_zone_row_number UNIQUE (zone_id, row_label, seat_number),
    CONSTRAINT fk_seats_zone FOREIGN KEY (zone_id) REFERENCES ticket_zones (id),
    CONSTRAINT ck_seats_status CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD', 'RESERVED_FOR_AUCTION')),
    CONSTRAINT ck_seats_seat_number CHECK (seat_number > 0),
    CONSTRAINT ck_seats_hold_consistency CHECK (
        (status = 'HELD' AND held_by_order_id IS NOT NULL AND hold_expires_at IS NOT NULL)
            OR (status <> 'HELD' AND held_by_order_id IS NULL AND hold_expires_at IS NULL)
        )
);
