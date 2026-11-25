package com.bidtix.booking.dtos;

import java.util.UUID;

/** One line of an order. A seated line names its seat; a standing one has no seat and may have a quantity above 1. */
public record OrderItemResponse(
        UUID zonePublicId,
        String zoneName,
        UUID seatPublicId,
        String rowLabel,
        Integer seatNumber,
        int quantity,
        /* VND, the price when the order was made */
        long unitPrice) {
}
