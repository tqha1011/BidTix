package com.bidtix.config.metrics;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BacklogGaugeTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicInteger reads = new AtomicInteger();
    private Long stuck = 3L;

    private final BacklogGauge gauge = BacklogGauge.register(registry, "bidtix.test.stuck", "Stuck things",
            Tags.of("module", "test"), () -> {
                reads.incrementAndGet();
                if (stuck == null) {
                    throw new IllegalStateException("database down");
                }
                return stuck;
            });

    @Test
    void itIsNotANumberUntilItHasBeenRead() {
        assertThat(value()).isNaN();
    }

    @Test
    void lookingAtItNeverReadsTheDatabase_onlyRefreshingDoes() {
        gauge.refresh();

        assertThat(value()).isEqualTo(3);
        assertThat(value()).isEqualTo(3);
        assertThat(reads).hasValue(1);

        stuck = 0L;
        gauge.refresh();
        assertThat(value()).as("it goes down again").isZero();
    }

    @Test
    void aFailedReadShowsNoNumber_ratherThanAnOldOne() {
        gauge.refresh();
        stuck = null;

        gauge.refresh();   // must not throw: the other gauges still have to be read

        assertThat(value()).isNaN();
        stuck = 5L;
        gauge.refresh();
        assertThat(value()).isEqualTo(5);
    }

    private double value() {
        return registry.get("bidtix.test.stuck").tag("module", "test").gauge().value();
    }
}
