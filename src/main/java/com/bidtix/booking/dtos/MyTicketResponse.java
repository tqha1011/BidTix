package com.bidtix.booking.dtos;

import com.bidtix.booking.domain.TicketStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * A ticket as its owner sees it. {@code ticketPublicId} is the QR payload shown at the door; {@code rowLabel} and
 * {@code seatNumber} are null for a standing ticket.
 */
public record MyTicketResponse(
        UUID ticketPublicId,
        TicketStatus status,
        UUID eventPublicId,
        String eventTitle,
        Instant eventStartsAt,
        String zoneName,
        String rowLabel,
        Integer seatNumber,
        String orderCode,
        Instant issuedAt) {
}
