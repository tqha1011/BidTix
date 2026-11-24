package com.bidtix.booking.domain;

import java.time.Instant;

/**
 * A payment is being applied to an order. Published inside the transaction that marks the order PAID, so a listener that
 * must stand or fall with the payment (an auction order settles its auction) runs in that same transaction. For an auction
 * order it is published just before the order row is updated (see {@code OrderPayment.applyPayment}), for a booking order just
 * after; a listener that throws rolls the payment back.
 *
 * @param totalAmount VND, the order total (money is never a double)
 */
public record OrderPaidEvent(Long orderId, String orderCode, OrderSource source, long totalAmount, Instant paidAt) {
}
