package com.bidtix.auction.dtos;

import com.bidtix.auction.domain.Auction;
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

    /** The event, zone and seat are stored as internal ids, so the caller hands in their public ids ({@code seatPublicId} is null for a standing ticket). */
    public static AuctionResponse from(Auction auction, UUID eventPublicId, UUID zonePublicId, UUID seatPublicId) {
        return new AuctionResponse(auction.getPublicId(), eventPublicId, zonePublicId, seatPublicId,
                auction.getStartPrice(), auction.getMinIncrement(), auction.getCurrentPrice(), auction.getBidCount(),
                auction.getStartsAt(), auction.getEndsAt(), auction.getAntiSnipeWindowSec(), auction.getExtensionSec(),
                auction.getStatus());
    }
}
