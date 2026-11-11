package com.bidtix.booking.dto;

import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.event.domain.ZoneType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outcome of a booking: one order plus the tickets issued for it.
 * <p>
 * {@code tickets} is empty while {@code status} is {@code PENDING_PAYMENT} (seats are held, payment
 * not completed); tickets exist only once the order is {@code PAID}.
 */
public record BookingTicketResult(
        String orderCode,
        OrderStatus status,
        long totalAmount,       // VND
        String currency,
        Instant expiresAt,      // payment deadline; the held seats are released after it
        Instant paidAt,         // null until paid
        String username,
        String eventName,
        String address,
        Instant startsAt,
        Instant endsAt,
        ZoneType zoneType,
        List<TicketInfo> tickets
) {

    /** {@code rowLabel} and {@code seatNumber} are null for general admission tickets. */
    public record TicketInfo(UUID ticketPublicId, String rowLabel, Integer seatNumber) {
    }

    /** Same booking after payment: new status, paid time and the issued tickets. */
    public BookingTicketResult withPayment(OrderStatus newStatus, Instant newPaidAt, List<TicketInfo> newTickets) {
        return new BookingTicketResult(orderCode, newStatus, totalAmount, currency, expiresAt, newPaidAt,
                username, eventName, address, startsAt, endsAt, zoneType, newTickets);
    }
}
