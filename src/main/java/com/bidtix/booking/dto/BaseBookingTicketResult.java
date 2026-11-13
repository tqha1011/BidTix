package com.bidtix.booking.dto;

import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.event.domain.ZoneType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outcome of a booking: one order plus the tickets issued for it. What differs between zone types is the
 * shape of a ticket, so there is one result type per zone type:
 * {@link BookingTicketSeatsResult} (SEATED) and {@link BookingTicketQuantityResult} (GENERAL_ADMISSION).
 * <p>
 * {@code tickets} is empty while {@code status} is {@code PENDING_PAYMENT} (stock is held, payment not
 * completed); tickets exist only once the order is {@code PAID}.
 */
public sealed interface BaseBookingTicketResult permits BookingTicketSeatsResult, BookingTicketQuantityResult {

    String orderCode();

    OrderStatus status();

    /** VND */
    long totalAmount();

    String currency();

    /** Payment deadline; the held seats / quantity are released after it. */
    Instant expiresAt();

    /** Null until paid. */
    Instant paidAt();

    String username();

    String eventName();

    String address();

    Instant startsAt();

    Instant endsAt();

    ZoneType zoneType();

    List<? extends TicketRef> tickets();

    /** What every kind of ticket has: the id that is also its QR payload. */
    interface TicketRef {
        UUID ticketPublicId();
    }
}
