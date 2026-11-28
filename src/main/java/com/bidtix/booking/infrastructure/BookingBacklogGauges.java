package com.bidtix.booking.infrastructure;

import com.bidtix.booking.domain.OrderSource;
import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.config.metrics.BacklogGauge;
import com.bidtix.event.domain.EventStatus;
import com.bidtix.event.infrastructure.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Work the booking jobs should have done by now, refreshed every 30 s (see {@link BacklogGauge}). Normally zero. */
@Component
public class BookingBacklogGauges {

    /** The confirmation grace period (10 minutes) and a margin for the job that runs every minute. */
    static final Duration EXPIRED_ORDER_LATE_AFTER = Duration.ofMinutes(15);
    /** An event is marked at the earliest 5 minutes after its cancellation. */
    static final Duration CLEANUP_LATE_AFTER = Duration.ofMinutes(10);

    private final List<BacklogGauge> gauges;

    public BookingBacklogGauges(MeterRegistry registry, OrderRepository orders, EventRepository events, Clock clock) {
        gauges = List.of(
                BacklogGauge.register(registry, "bidtix.orders.expired.uncancelled",
                        "Booking orders still unpaid 15 minutes after their deadline", Tags.empty(),
                        () -> orders.countByStatusAndSourceAndExpiresAtBefore(OrderStatus.PENDING_PAYMENT,
                                OrderSource.BOOKING, clock.instant().minus(EXPIRED_ORDER_LATE_AFTER))),
                BacklogGauge.register(registry, "bidtix.events.cancelled.uncleaned",
                        "Events cancelled 10 minutes ago that a module has not cleaned up", Tags.of("module", "booking"),
                        () -> events.countByStatusAndOrdersCleanedAtIsNullAndCancelledAtBefore(EventStatus.CANCELLED,
                                clock.instant().minus(CLEANUP_LATE_AFTER))));
    }

    @Scheduled(fixedDelay = 30_000)
    public void refresh() {
        gauges.forEach(BacklogGauge::refresh);
    }
}
