package com.bidtix.payment.dtos;

/**
 * Where to send the customer to pay one order. The client redirects the browser to {@code paymentUrl}; the result reaches
 * the server separately (the provider's callback), so this response says nothing about whether the payment succeeded.
 */
public record PaymentUrlResponse(String paymentUrl) {
}
