package com.bidtix.payment.service;

import com.bidtix.payment.infrastructure.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** For other modules: asks for the money of a paid order to be given back. */
@Service
@RequiredArgsConstructor
public class RefundRequestService {

    private final PaymentRepository paymentRepository;
    private final Clock clock;

    /**
     * Moves the order's paid payment SUCCEEDED -&gt; REFUND_PENDING. Nothing is sent to the provider here:
     * {@code RefundPaymentJob} does that, after the commit.
     * <p>
     * It must be called inside the caller's transaction ({@code MANDATORY}), so the refund request commits or rolls back
     * together with whatever made it necessary. Returns false when the order has no SUCCEEDED payment (never paid, or the
     * refund was already asked for); the caller decides whether that undoes its own change.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean requestRefund(long orderId, String reason) {
        return paymentRepository.markRefundPendingByOrderId(orderId, reason, clock.instant()) == 1;
    }
}
