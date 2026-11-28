package com.bidtix.config.metrics;

import com.bidtix.config.exceptions.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * The application's own counters. Every tag takes its values from a fixed set (never a user, event or order id), and
 * every series is registered at zero on startup, so a dashboard shows it before the first failure and
 * {@code increase()} sees that one too. Count after the transaction that did the work has ended, or a rolled back change
 * is counted as done.
 */
@Component
public class Metrics {

    /** The jobs that handle their items one by one and carry on when one fails. */
    public enum Job {
        ORDER_CANCELLATION, CANCELLED_EVENT_ORDERS, CANCELLED_EVENT_AUCTIONS, AUCTION_CLOSING, OFFER_EXPIRY, REFUND,
        PAYMENT_PENDING
    }

    /** The codes {@code PaymentService.processVnPayResponse} answers VNPAY with. */
    private static final List<String> VNPAY_IPN_CODES = List.of("00", "01", "02", "04", "97", "99");

    private final MeterRegistry registry;

    public Metrics(MeterRegistry registry) {
        this.registry = registry;
        for (Job job : Job.values()) {
            jobFailures(job);
        }
        VNPAY_IPN_CODES.forEach(this::vnpayIpn);
        for (ErrorCode errorCode : ErrorCode.values()) {
            apiErrors(errorCode);
        }
    }

    /**
     * One item a job could not handle. The job itself goes on and still counts as a successful run in
     * {@code tasks.scheduled.execution}, so this is where a failing item shows.
     */
    public void jobItemFailed(Job job) {
        jobFailures(job).increment();
    }

    /** One answer to VNPAY's confirmation, successful or not. The route always answers 200: only the code tells. */
    public void vnpayIpnAnswered(String rspCode) {
        vnpayIpn(rspCode).increment();
    }

    /** One error response of the API, by its code. */
    public void apiError(ErrorCode errorCode) {
        apiErrors(errorCode).increment();
    }

    private Counter jobFailures(Job job) {
        return registry.counter("bidtix.job.item.failures", "job", job.name().toLowerCase(Locale.ROOT));
    }

    private Counter vnpayIpn(String rspCode) {
        return registry.counter("bidtix.payment.vnpay.ipn", "code", rspCode);
    }

    private Counter apiErrors(ErrorCode errorCode) {
        return registry.counter("bidtix.api.errors", "code", errorCode.name());
    }
}
