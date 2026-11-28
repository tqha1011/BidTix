package com.bidtix.config.metrics;

import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.config.metrics.Metrics.Job;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static com.bidtix.support.Counters.jobFailures;
import static com.bidtix.support.Counters.value;
import static org.assertj.core.api.Assertions.assertThat;

class MetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Metrics metrics = new Metrics(registry);

    /** A series that only appears with its first failure shows "no data" until then, and increase() misses that one. */
    @Test
    void everySeriesExistsAtZeroFromTheStart() {
        for (Job job : Job.values()) {
            assertThat(registry.find("bidtix.job.item.failures").tag("job", job.name().toLowerCase(Locale.ROOT))
                    .counter()).as(job.name()).isNotNull();
        }
        for (String code : new String[]{"00", "01", "02", "04", "97", "99"}) {
            assertThat(registry.find("bidtix.payment.vnpay.ipn").tag("code", code).counter()).as(code).isNotNull();
        }
        for (ErrorCode error : ErrorCode.values()) {
            assertThat(registry.find("bidtix.api.errors").tag("code", error.name()).counter()).as(error.name())
                    .isNotNull();
        }
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.measure().iterator().next().getValue()).isZero());
    }

    @Test
    void eachCallCountsOnItsOwnSeries() {
        metrics.jobItemFailed(Job.REFUND);
        metrics.vnpayIpnAnswered("99");
        metrics.vnpayIpnAnswered("99");
        metrics.apiError(ErrorCode.ZONE_SOLD_OUT);

        assertThat(jobFailures(registry, "refund")).isEqualTo(1);
        assertThat(jobFailures(registry, "auction_closing")).isZero();
        assertThat(value(registry, "bidtix.payment.vnpay.ipn", "code", "99")).isEqualTo(2);
        assertThat(value(registry, "bidtix.api.errors", "code", "ZONE_SOLD_OUT")).isEqualTo(1);
    }
}
