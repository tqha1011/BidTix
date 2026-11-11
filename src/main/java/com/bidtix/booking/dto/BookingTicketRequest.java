package com.bidtix.booking.dto;

import java.util.List;
import java.util.UUID;

public record BookingTicketRequest(
        UUID eventPublicId,
        UUID zonePublicId,
        List<UUID> seatPublicIds, // nullable if the zone is not a seat-based zone
        Integer quantity
) {
}
