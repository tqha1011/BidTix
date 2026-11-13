package com.bidtix.event.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * The cross-field rules (end after start, sales end before the show starts) are checked in
 * {@code EventService}, which can say exactly which rule failed.
 */
public record CreateEventRequest(
        @NotNull UUID venuePublicId,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 10_000) String description,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @NotNull Instant saleStartsAt,
        @NotNull Instant saleEndsAt,
        /* optional: the service uses 4 when it is missing */
        @Min(1) @Max(100) Integer maxTicketsPerUser) {
}
