package com.bidtix.booking.dtos;

import com.bidtix.booking.domain.OrderSource;
import com.bidtix.booking.domain.OrderStatus;

import java.time.Instant;
import java.util.UUID;

/** An order as its customer sees it in a list. {@code expiresAt} is the payment deadline; {@code paidAt} is null until paid. */
public record OrderSummaryResponse(
        String orderCode,
        OrderSource source,
        OrderStatus status,
        /* VND */
        long totalAmount,
        String currency,
        Instant createdAt,
        Instant expiresAt,
        Instant paidAt,
        UUID eventPublicId,
        String eventTitle) {
}
