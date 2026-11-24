package com.bidtix.support;

import com.bidtix.payment.service.PayableOrders;
import com.bidtix.payment.service.PayableOrders.Applied;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

/**
 * Pays an order the way the payment module will once a provider confirms it: through {@link PayableOrders#applyPayment}.
 * Booking no longer takes payment itself, so a test that needs a PAID order with its tickets books it and then calls this.
 */
public final class OrderPayments {

    private OrderPayments() {
    }

    /** Marks the order with this code paid now and issues its tickets; returns what {@code applyPayment} answered. */
    public static Applied pay(PayableOrders orders, JdbcTemplate jdbc, String orderCode) {
        Long orderId = jdbc.queryForObject("select id from orders where order_code = ?", Long.class, orderCode);
        return orders.applyPayment(orderId, Instant.now());
    }
}
