package com.bidtix.auction.dtos;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record PlaceBidRequest(
        @NotNull UUID auctionPublicId,
        /* VND. Boxed on purpose, like CreateAuctionRequest: a missing value is an ordinary validation error */
        @NotNull @Positive Long amount
) {
    public static PlaceBidRequest create(UUID auctionPublicId, Long amount) {
        return new PlaceBidRequest(auctionPublicId, amount);
    }
}
