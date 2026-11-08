package com.bidtix.event.domain;

public enum SeatStatus {
    AVAILABLE,
    /** Temporarily reserved by a pending order until {@code hold_expires_at}. */
    HELD,
    SOLD,
    /** Taken out of normal sale because it is being auctioned. */
    RESERVED_FOR_AUCTION
}
