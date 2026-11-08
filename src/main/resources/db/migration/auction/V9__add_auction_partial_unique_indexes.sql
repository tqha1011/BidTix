-- Module: auction

-- A seat can be in at most one active auction. Finished auctions (SETTLED/FAILED/CANCELLED)
-- are excluded so a seat can be re-auctioned after a failed or cancelled auction.
CREATE UNIQUE INDEX ux_auctions_active_seat
    ON auctions (seat_id)
    WHERE seat_id IS NOT NULL AND status IN ('SCHEDULED', 'LIVE', 'AWAITING_PAYMENT');

-- At most one open (PENDING) offer per auction, so two bidders never hold the right to buy
-- the same ticket at the same time. UNIQUE (auction_id, offer_rank) alone does not prevent
-- rank 2 being opened while rank 1 is still pending.
CREATE UNIQUE INDEX ux_auction_offers_one_pending
    ON auction_offers (auction_id)
    WHERE status = 'PENDING';
