package com.bidtix.payment.infrastructure;

import com.bidtix.config.metrics.BacklogGauge;
import com.bidtix.payment.domain.PaymentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Money that is waiting for something, refreshed every 30 s (see {@link BacklogGauge}). All but the number of refunds
 * waiting (and their age) should normally be zero, with one exception: a payment page the customer left stays PENDING
 * unless VNPAY reports it as failed ({@link PaymentPendingCancelJob}), so stale PENDING payments add up.
 */
@Component
public class PaymentBacklogGauges {

    static final Duration PENDING_STALE_AFTER = Duration.ofMinutes(30);
    static final Duration WEBHOOK_LATE_AFTER = Duration.ofMinutes(5);

    private final List<BacklogGauge> gauges;

    public PaymentBacklogGauges(MeterRegistry registry, PaymentRepository payments,
                                PaymentWebhookEventRepository callbacks, Clock clock) {
        gauges = List.of(
                BacklogGauge.register(registry, "bidtix.payments.refund.pending", "Payments waiting for a refund",
                        Tags.empty(), () -> payments.countByStatus(PaymentStatus.REFUND_PENDING)),
                // from the payment's creation: no column says when it started to wait
                BacklogGauge.register(registry, "bidtix.payments.refund.oldest.age.seconds",
                        "Age of the oldest payment waiting for a refund, from its creation", Tags.empty(),
                        () -> payments.findOldestRefundPendingCreation()
                                .map(created -> Duration.between(created, clock.instant()).toSeconds())
                                .orElse(0L)),
                BacklogGauge.register(registry, "bidtix.payments.refund.stuck",
                        "Refunds the job will not finish by itself: a person has to look", Tags.empty(),
                        () -> payments.countRefundsNeedingAPerson(RefundPaymentJob.MAX_REFUND_ATTEMPTS,
                                RefundPaymentJob.MAX_REFUND_QUERIES)),
                BacklogGauge.register(registry, "bidtix.payments.pending.stale",
                        "Payments still without a result 30 minutes after they were made", Tags.empty(),
                        () -> payments.countByStatusAndCreatedAtBefore(PaymentStatus.PENDING,
                                clock.instant().minus(PENDING_STALE_AFTER))),
                BacklogGauge.register(registry, "bidtix.payments.webhooks.unprocessed",
                        "Stored provider callbacks not processed 5 minutes after they came in", Tags.empty(),
                        () -> callbacks.countByProcessedAtIsNullAndCreatedAtBefore(
                                clock.instant().minus(WEBHOOK_LATE_AFTER))));
    }

    @Scheduled(fixedDelay = 30_000)
    public void refresh() {
        gauges.forEach(BacklogGauge::refresh);
    }
}
