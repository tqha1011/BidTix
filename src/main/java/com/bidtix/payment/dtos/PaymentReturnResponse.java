package com.bidtix.payment.dtos;

import com.bidtix.payment.domain.PaymentStatus;

/**
 * What to show a customer who comes back from the provider's page. {@code paidAtProvider} is what the signed return
 * parameters say happened there; {@code paymentStatus} is what is recorded here, which stays PENDING until the
 * provider's callback arrives. A client should wait for that (or read the order) before saying the ticket is theirs.
 */
public record PaymentReturnResponse(
        String orderCode,
        /* VND */
        long amount,
        boolean paidAtProvider,
        PaymentStatus paymentStatus) {
}
