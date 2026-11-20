package com.bidtix.auction.dtos;

import java.math.BigDecimal;
import java.util.UUID;

public record BidResponse(
        UUID auctionPublicId,
        String eventName,
        String bidderWinUsername,
        BigDecimal amount,
        int seatNumber,
        String rowLabel,
        String zoneName,
        String address
) {
    public static BidResponse from(UUID auctionPublicId, String eventName, String bidderWinUsername, BigDecimal amount,
                                   int seatNumber, String rowLabel, String zoneName, String address) {
        return new BidResponse(auctionPublicId, eventName, bidderWinUsername, amount, seatNumber, rowLabel, zoneName, address);
    }
}
