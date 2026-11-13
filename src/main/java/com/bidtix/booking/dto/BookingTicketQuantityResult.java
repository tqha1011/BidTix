package com.bidtix.booking.dto;

import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.event.domain.ZoneType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Booking of a GENERAL_ADMISSION zone: sold by quantity, so a ticket has no seat. */
public record BookingTicketQuantityResult(
        String orderCode,
        OrderStatus status,
        long totalAmount,       // VND
        String currency,
        Instant expiresAt,      // payment deadline; the held quantity is released after it
        Instant paidAt,         // null until paid
        String username,
        String eventName,
        String address,
        Instant startsAt,
        Instant endsAt,
        ZoneType zoneType,
        int quantity,
        List<TicketQuantityInfo> tickets
) implements BaseBookingTicketResult {

    public record TicketQuantityInfo(UUID ticketPublicId) implements TicketRef {
    }

    /** Same booking after payment: new status, paid time and the issued tickets. */
    public BookingTicketQuantityResult withPayment(OrderStatus newStatus, Instant newPaidAt,
                                                   List<TicketQuantityInfo> newTickets) {
        return new BookingTicketQuantityResult(orderCode, newStatus, totalAmount, currency, expiresAt, newPaidAt,
                username, eventName, address, startsAt, endsAt, zoneType, quantity, newTickets);
    }
}
