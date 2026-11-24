package com.bidtix.auction.domain;

/** One bidder of an auction and their highest bid there (VND). Not an entity: it is read from the bids, and cached in Redis. */
public record LeaderboardEntry(
        Long bidderId,
        long amount
) {
}
