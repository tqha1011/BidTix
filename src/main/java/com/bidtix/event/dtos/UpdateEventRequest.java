package com.bidtix.event.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * The whole event as it should be afterwards (PUT). On a published event only {@code title} and {@code description} may
 * differ from what it has now; {@code maxTicketsPerUser} may be left out to keep it.
 */
public record UpdateEventRequest(
        @NotNull UUID venuePublicId,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 10_000) String description,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @NotNull Instant saleStartsAt,
        @NotNull Instant saleEndsAt,
        @Min(1) @Max(100) Integer maxTicketsPerUser) {
}
