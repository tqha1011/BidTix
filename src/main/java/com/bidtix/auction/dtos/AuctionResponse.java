package com.bidtix.auction.dtos;

import com.bidtix.auction.domain.AuctionStatus;

import java.time.Instant;
import java.util.UUID;

/** What anybody may know about an auction. The reserve price is deliberately not here: it is a hidden floor. */
public record AuctionResponse(
        UUID publicId,
        UUID eventPublicId,
        UUID zonePublicId,
        UUID seatPublicId,
        long startPrice,
        long minIncrement,
        Long currentPrice,
        int bidCount,
        Instant startsAt,
        Instant endsAt,
        int antiSnipeWindowSec,
        int extensionSec,
        AuctionStatus status) {
}
