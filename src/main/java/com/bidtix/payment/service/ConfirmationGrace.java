package com.bidtix.payment.service;

import com.bidtix.payment.infrastructure.PaymentProperties;
import com.bidtix.payment.infrastructure.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/**
 * The grace period of an unpaid order. The provider's page closes at the order's deadline, but its callback arrives some
 * time after the customer paid. So whoever releases the stock of an expired order asks here first: an order with a
 * PENDING payment is released only once {@code bidtix.payment.confirmation-grace} has passed after the deadline too; an
 * order nobody tried to pay (or whose payments all have a result) gets no extra time. Customers get no extra time to pay.
 * <p>
 * Call it inside the transaction that releases the stock. No payment page is created for an order that close to its
 * deadline, so after the deadline an order cannot start waiting for a new callback.
 */
@Component
@RequiredArgsConstructor
public class ConfirmationGrace {

    private final PaymentRepository paymentRepository;
    private final PaymentProperties properties;

    /** Those of these orders that have a payment still waiting for its result. */
    public Set<Long> ordersAwaitingConfirmation(Collection<Long> orderIds) {
        return paymentRepository.findOrderIdsWithPendingPayment(orderIds);
    }

    /** An awaiting order whose deadline is at or before this moment has had its whole grace period. */
    public Instant cutoff(Instant now) {
        return now.minus(properties.confirmationGrace());
    }
}
