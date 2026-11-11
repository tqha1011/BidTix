package com.bidtix.payment.service;

/**
 * Charges a customer for an order. Talks to the outside world, so callers must NOT invoke it
 * inside a database transaction.
 * <p>
 * Only a mock implementation exists so far ({@code MockPaymentGateway}); replace it with a real
 * provider (and webhook handling) later without touching the booking flow.
 */
public interface PaymentGateway {

    /**
     * @param orderCode the order being paid
     * @param amount    amount in the smallest currency unit (VND)
     * @return {@code true} when the payment succeeded
     */
    boolean charge(String orderCode, long amount);
}
