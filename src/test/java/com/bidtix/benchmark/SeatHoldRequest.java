package com.bidtix.benchmark;

import java.time.Instant;
import java.util.List;

/**
 * Hold these seats for this order. {@code seatIds} are internal ids in the order the "customer" listed them,
 * i.e. NOT sorted: sorting before locking is the strategy's job. The order already exists
 * ({@code seats.held_by_order_id} has an FK to it), so the cost measured is only the concurrency control.
 */
public record SeatHoldRequest(long zoneId, List<Long> seatIds, long orderId, Instant expiresAt) {
}
