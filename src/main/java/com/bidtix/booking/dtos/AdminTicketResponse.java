package com.bidtix.booking.dtos;

import com.bidtix.booking.domain.TicketStatus;

import java.time.Instant;
import java.util.UUID;

/** One row of the admin ticket list. {@code rowLabel} and {@code seatNumber} are null for general admission. */
public record AdminTicketResponse(
        UUID ticketPublicId,
        TicketStatus status,
        Instant usedAt,
        UUID zonePublicId,
        String zoneName,
        String rowLabel,
        Integer seatNumber,
        UUID ownerPublicId,
        String ownerEmail,
        String orderCode,
        Instant createdAt) {
}
