package com.bidtix.booking.dtos;

import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.event.domain.ZoneType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Booking of a SEATED zone: every ticket is tied to one seat. */
public record BookingTicketSeatsResult(
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
        List<TicketSeatsInfo> tickets
) implements BaseBookingTicketResult {

    public record TicketSeatsInfo(UUID ticketPublicId, String rowLabel, Integer seatNumber) implements TicketRef {
    }

    /** Same booking after payment: new status, paid time and the issued tickets. */
    public BookingTicketSeatsResult withPayment(OrderStatus newStatus, Instant newPaidAt, List<TicketSeatsInfo> newTickets) {
        return new BookingTicketSeatsResult(orderCode, newStatus, totalAmount, currency, expiresAt, newPaidAt,
                username, eventName, address, startsAt, endsAt, zoneType, newTickets);
    }
}
