package com.bidtix.auction.domain;

public enum AuctionStatus {
    SCHEDULED,
    LIVE,
    /** Ended with a winner; an offer is waiting for payment. */
    AWAITING_PAYMENT,
    SETTLED,
    /** No bids, reserve not met, or every offer expired. */
    FAILED,
    CANCELLED
}
