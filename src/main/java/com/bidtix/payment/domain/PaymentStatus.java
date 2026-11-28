package com.bidtix.payment.domain;

public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    /** The provider said the transaction failed. */
    FAILED,
    /**
     * Nobody paid, as far as the provider says: the last query about it still found no payment. Unlike {@link #FAILED},
     * no transaction was reported as failed; we stopped waiting.
     */
    CANCELLED,
    /** The provider confirmed it, but it cannot be applied to its order (expired, cancelled, or already paid): give the money back. */
    REFUND_PENDING,
    REFUNDED
}
