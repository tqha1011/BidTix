package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.AuctionStatus;
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

/** Work the auction jobs should have done by now, refreshed every 30 s (see {@link BacklogGauge}). Normally zero. */
@Component
public class AuctionBacklogGauges {

    /** The closing job runs every minute. */
    static final Duration CLOSING_LATE_AFTER = Duration.ofMinutes(5);
    /** An event is marked at the earliest 5 minutes after its cancellation. */
    static final Duration CLEANUP_LATE_AFTER = Duration.ofMinutes(10);

    private final List<BacklogGauge> gauges;

    public AuctionBacklogGauges(MeterRegistry registry, AuctionRepository auctions, EventRepository events, Clock clock) {
        gauges = List.of(
                BacklogGauge.register(registry, "bidtix.auctions.overdue",
                        "Auctions still LIVE 5 minutes after their end", Tags.empty(),
                        () -> auctions.countByStatusAndEndsAtBefore(AuctionStatus.LIVE,
                                clock.instant().minus(CLOSING_LATE_AFTER))),
                BacklogGauge.register(registry, "bidtix.events.cancelled.uncleaned",
                        "Events cancelled 10 minutes ago that a module has not cleaned up", Tags.of("module", "auction"),
                        () -> events.countByStatusAndAuctionsCleanedAtIsNullAndCancelledAtBefore(EventStatus.CANCELLED,
                                clock.instant().minus(CLEANUP_LATE_AFTER))));
    }

    @Scheduled(fixedDelay = 30_000)
    public void refresh() {
        gauges.forEach(BacklogGauge::refresh);
    }
}
