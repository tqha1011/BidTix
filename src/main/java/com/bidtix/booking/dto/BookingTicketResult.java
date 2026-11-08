package com.bidtix.booking.dto;

import com.bidtix.event.domain.ZoneType;

import java.time.Instant;

public record BookingTicketResult(
        String username,
        String eventName,
        String address,
        String rowLabel,
        Integer seatNumber,
        ZoneType zoneType,
        String orderCode,
        Instant startsAt,
        Instant endsAt
) {
}
