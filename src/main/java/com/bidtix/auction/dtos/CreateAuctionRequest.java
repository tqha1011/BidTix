package com.bidtix.auction.dtos;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.UUID;

/**
 * Sells one ticket: one seat of a SEATED zone ({@code seatPublicId} required) or one unit of a GENERAL_ADMISSION zone
 * ({@code seatPublicId} must be left out). Which of the two applies depends on the zone, and the rules that compare
 * several fields (reserve vs start price, end vs start, the event's dates) are checked in {@code AuctionService}, which
 * can say exactly which one failed.
 */
public record CreateAuctionRequest(
        @NotNull UUID eventPublicId,
        @NotNull UUID zonePublicId,
        UUID seatPublicId,
        /* VND. Boxed on purpose: a missing primitive makes the JSON unreadable (a 400 without our error format),
           while a missing Long is an ordinary validation error */
        @NotNull @Positive Long startPrice,
        /* VND; each bid must beat the current price by at least this */
        @NotNull @Positive Long minIncrement,
        /* VND, optional hidden floor: below it the auction fails */
        @Positive Long reservePrice,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        /* optional: a bid in the last this-many seconds extends the auction; the service uses 60 when missing */
        @Min(0) Integer antiSnipeWindowSec,
        /* optional: how many seconds such a bid adds; the service uses 60 when missing */
        @Min(0) Integer extensionSec) {
}
