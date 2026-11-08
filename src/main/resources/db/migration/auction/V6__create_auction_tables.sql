-- Module: auction (English auction, one ticket per auction)

CREATE TABLE auctions
(
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    event_id              BIGINT      NOT NULL,
    zone_id               BIGINT      NOT NULL,
    seat_id               BIGINT, -- SEATED zone: the auctioned seat; NULL for one GENERAL_ADMISSION unit
    start_price           BIGINT      NOT NULL, -- VND
    min_increment         BIGINT      NOT NULL,
    reserve_price         BIGINT,               -- hidden floor; below it the auction FAILS
    current_price         BIGINT,               -- highest accepted bid (denormalized)
    leading_bidder_id     BIGINT,
    bid_count             INT         NOT NULL DEFAULT 0,
    starts_at             TIMESTAMPTZ NOT NULL,
    ends_at               TIMESTAMPTZ NOT NULL, -- pushed forward by anti-sniping
    anti_snipe_window_sec INT         NOT NULL DEFAULT 60,
    extension_sec         INT         NOT NULL DEFAULT 60,
    status                VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    version               BIGINT      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_auctions_public_id UNIQUE (public_id),
    CONSTRAINT fk_auctions_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_auctions_zone FOREIGN KEY (zone_id) REFERENCES ticket_zones (id),
    CONSTRAINT fk_auctions_seat FOREIGN KEY (seat_id) REFERENCES seats (id),
    CONSTRAINT fk_auctions_leading_bidder FOREIGN KEY (leading_bidder_id) REFERENCES users (id),
    CONSTRAINT ck_auctions_prices CHECK (start_price > 0 AND min_increment > 0),
    CONSTRAINT ck_auctions_reserve_price CHECK (reserve_price IS NULL OR reserve_price >= start_price),
    CONSTRAINT ck_auctions_current_price CHECK (current_price IS NULL OR current_price >= start_price),
    CONSTRAINT ck_auctions_leader CHECK ((current_price IS NULL) = (leading_bidder_id IS NULL)),
    CONSTRAINT ck_auctions_bid_count CHECK (bid_count >= 0),
    CONSTRAINT ck_auctions_time CHECK (ends_at > starts_at),
    CONSTRAINT ck_auctions_anti_snipe CHECK (anti_snipe_window_sec >= 0 AND extension_sec >= 0),
    CONSTRAINT ck_auctions_status CHECK (status IN ('SCHEDULED', 'LIVE', 'AWAITING_PAYMENT', 'SETTLED', 'FAILED',
                                                    'CANCELLED'))
);

CREATE INDEX idx_auctions_event_id ON auctions (event_id);
CREATE INDEX idx_auctions_zone_id ON auctions (zone_id);
CREATE INDEX idx_auctions_seat_id ON auctions (seat_id);
CREATE INDEX idx_auctions_leading_bidder_id ON auctions (leading_bidder_id);

-- Append-only: only accepted bids are stored.
CREATE TABLE bids
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id  UUID        NOT NULL DEFAULT gen_random_uuid(),
    auction_id BIGINT      NOT NULL,
    bidder_id  BIGINT      NOT NULL,
    amount     BIGINT      NOT NULL, -- VND
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_bids_public_id UNIQUE (public_id),
    -- two concurrent bids with the same amount cannot both be accepted;
    -- also serves as the index for fk_bids_auction (auction_id is the leading column)
    CONSTRAINT uq_bids_auction_amount UNIQUE (auction_id, amount),
    CONSTRAINT fk_bids_auction FOREIGN KEY (auction_id) REFERENCES auctions (id),
    CONSTRAINT fk_bids_bidder FOREIGN KEY (bidder_id) REFERENCES users (id),
    CONSTRAINT ck_bids_amount CHECK (amount > 0)
);

CREATE INDEX idx_bids_bidder_id ON bids (bidder_id);

-- Right to buy at the bid price before expires_at.
-- offer_rank 1 = winner; if they do not pay, the next-highest distinct bidder gets the next rank.
CREATE TABLE auction_offers
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    auction_id BIGINT      NOT NULL,
    bid_id     BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    offer_rank INT         NOT NULL,
    order_id   BIGINT, -- AUCTION-source order created together with the offer
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- also serves as the index for fk_auction_offers_auction (auction_id is the leading column)
    CONSTRAINT uq_auction_offers_auction_rank UNIQUE (auction_id, offer_rank),
    CONSTRAINT uq_auction_offers_order UNIQUE (order_id),
    CONSTRAINT fk_auction_offers_auction FOREIGN KEY (auction_id) REFERENCES auctions (id),
    CONSTRAINT fk_auction_offers_bid FOREIGN KEY (bid_id) REFERENCES bids (id),
    CONSTRAINT fk_auction_offers_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_auction_offers_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_auction_offers_rank CHECK (offer_rank > 0),
    CONSTRAINT ck_auction_offers_status CHECK (status IN ('PENDING', 'ACCEPTED', 'EXPIRED'))
);

CREATE INDEX idx_auction_offers_bid_id ON auction_offers (bid_id);
CREATE INDEX idx_auction_offers_user_id ON auction_offers (user_id);
