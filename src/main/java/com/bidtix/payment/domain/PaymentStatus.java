package com.bidtix.payment.domain;

public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    /** The provider confirmed it, but it cannot be applied to its order (expired, cancelled, or already paid): give the money back. */
    REFUND_PENDING,
    REFUNDED
}
