package com.bidtix.booking.dtos;

import java.time.Instant;
import java.util.UUID;

/** What the gate sees for an admitted ticket. {@code rowLabel} and {@code seatNumber} are null for general admission. */
public record CheckInResponse(
        UUID ticketPublicId,
        String eventTitle,
        String zoneName,
        String rowLabel,
        Integer seatNumber,
        String ownerName,
        String orderCode,
        Instant usedAt) {
}
