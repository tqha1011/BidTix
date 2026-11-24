package com.bidtix.auction.dtos;

/** One line of an auction's leaderboard: a bidder and their highest bid in that auction. */
public record LeaderboardEntryResponse(
        /* 1 is the highest bid */
        int rank,
        /* the bidder's name as they registered it; never the email or an id */
        String bidderName,
        /* VND: the bidder's highest bid in this auction */
        long amount
) {
    public static LeaderboardEntryResponse of(int rank, String bidderName, long amount) {
        return new LeaderboardEntryResponse(rank, bidderName, amount);
    }
}
