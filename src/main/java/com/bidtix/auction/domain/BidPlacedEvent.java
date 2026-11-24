package com.bidtix.auction.domain;

import java.util.UUID;

/**
 * An accepted bid. Published inside the bid transaction; listeners that touch anything outside the database (the Redis
 * leaderboard) use {@code AFTER_COMMIT}, so a rolled-back bid never reaches them.
 */
public record BidPlacedEvent(UUID auctionPublicId, Long bidderId, long amount) {
}
