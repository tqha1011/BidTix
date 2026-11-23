package com.bidtix.booking.dtos;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record BookingTicketRequest(
        @NotNull UUID eventPublicId,
        @NotNull UUID zonePublicId,
        List<UUID> seatPublicIds, // nullable if the zone is not a seat-based zone
        Integer quantity
) {
}
