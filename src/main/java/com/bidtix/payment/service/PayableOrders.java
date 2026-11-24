package com.bidtix.payment.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the payment module needs from orders, and nothing more. The booking module implements it, so {@code booking}
 * depends on {@code payment} and never the other way round (no cycle between the modules). The payment module sees an
 * order only as a {@link PayableOrder}: it never touches the {@code Order} entity or its repository.
 * <p>
 * Used by the two flows that start from a payment: creating the payment page (by order code, for the logged-in
 * customer) and handling the provider's callback (by the order id stored on the payment).
 */
public interface PayableOrders {

    /**
     * An order as the payment module needs to see it.
     *
     * @param id              internal order id, stored on the payment row
     * @param code            the order code shown to people
     * @param amount          VND, the order total
     * @param deadline        when the order expires
     * @param awaitingPayment true while the order is PENDING_PAYMENT; a paid, cancelled or expired order is not payable
     */
    record PayableOrder(Long id, String code, long amount, Instant deadline, boolean awaitingPayment) {
    }

    /** The result of {@link #applyPayment}. */
    enum Applied {
        /** The order is now paid (and its tickets issued, or whatever the order's owner does when it is paid). */
        APPLIED,
        /** Another payment already paid this order: the money of this one must be refunded. */
        ALREADY_PAID,
        /** The order expired or was cancelled meanwhile, and its stock may belong to someone else now: the money must be refunded. */
        NOT_PAYABLE
    }

    /** The caller's own order by its code. Empty for an unknown code and for someone else's order, so codes cannot be probed. */
    Optional<PayableOrder> findOwnOrder(String orderCode, UUID userPublicId);

    /** The order a payment belongs to, by the id on the payment row. */
    Optional<PayableOrder> findOrder(Long orderId);

    /**
     * The provider confirmed the money for this order: mark it paid and do what follows (issue the tickets, settle the
     * auction). It joins the caller's transaction, so the payment's own status change and this commit or roll back together.
     * It must decide with one conditional update and check its row count, so two confirmations (or a confirmation and the
     * job that cancels expired orders) cannot both win: the loser gets {@link Applied#ALREADY_PAID} or {@link Applied#NOT_PAYABLE}, whichever the order's
     * state says. Both mean "refund"; the difference is the reason stored on the payment.
     * <p>
     * If a reserved seat is no longer held by the order, it throws {@code BusinessException(ORDER_NOT_PAYABLE)} instead, and
     * the transaction must roll back; the caller then treats the payment as {@link Applied#NOT_PAYABLE} in a new transaction.
     */
    Applied applyPayment(Long orderId, Instant paidAt);
}
