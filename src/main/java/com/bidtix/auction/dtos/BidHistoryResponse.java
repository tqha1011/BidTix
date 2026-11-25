package com.bidtix.auction.dtos;

import java.time.Instant;

/** One accepted bid in an auction's history: the bidder's name as registered, never an email or an id. */
public record BidHistoryResponse(
        String bidderName,
        /* VND */
        long amount,
        Instant placedAt) {
}
