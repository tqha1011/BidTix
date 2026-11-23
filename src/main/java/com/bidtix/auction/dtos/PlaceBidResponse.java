package com.bidtix.auction.dtos;

import java.util.UUID;

public record PlaceBidResponse(
        UUID bidderPublicId,
        UUID auctionPublicId,
        Long amount,
        String username
) {
    public static PlaceBidResponse create(UUID bidderPublicId, UUID auctionPublicId, Long amount, String username) {
        return new PlaceBidResponse(bidderPublicId, auctionPublicId, amount, username);
    }
}
