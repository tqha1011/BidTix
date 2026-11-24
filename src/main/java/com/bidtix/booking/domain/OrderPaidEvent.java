package com.bidtix.booking.domain;

import java.time.Instant;

/**
 * An order has just been marked PAID. Published inside the transaction that marks it, so a listener that must stand or
 * fall with the payment (an auction order settles its auction) runs in that same transaction.
 *
 * @param totalAmount VND, the order total (money is never a double)
 */
public record OrderPaidEvent(Long orderId, String orderCode, OrderSource source, long totalAmount, Instant paidAt) {
}
