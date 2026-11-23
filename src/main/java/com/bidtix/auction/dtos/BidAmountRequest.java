package com.bidtix.auction.dtos;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Body of {@code POST /auctions/{auctionPublicId}/bids}: the auction comes from the path, the bidder from the token. */
public record BidAmountRequest(
        /* VND. Boxed on purpose: a missing value is an ordinary validation error, not an unreadable JSON */
        @NotNull @Positive Long amount
) {
}
