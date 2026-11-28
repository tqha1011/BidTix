package com.bidtix.config.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * A gauge of something left undone. Its number is read from the database only when the owner calls {@link #refresh}, on a
 * schedule: Prometheus reads the number in memory, however often it asks. It is {@code NaN} until the first read and
 * after a failed one, because an old number would look fine while nothing is known. With several application instances
 * every instance reports the same count: aggregate with {@code max}, not {@code sum}.
 */
@Slf4j
public final class BacklogGauge {

    private final String name;
    private final Supplier<? extends Number> read;
    private volatile double value = Double.NaN;

    private BacklogGauge(String name, Supplier<? extends Number> read) {
        this.name = name;
        this.read = read;
    }

    public static BacklogGauge register(MeterRegistry registry, String name, String description, Tags tags,
                                        Supplier<? extends Number> read) {
        BacklogGauge backlog = new BacklogGauge(name, read);
        Gauge.builder(name, backlog, gauge -> gauge.value)
                .description(description)
                .tags(tags)
                .strongReference(true)   // otherwise the registry holds it weakly
                .register(registry);
        return backlog;
    }

    /** Reads the number. Never throws, so one failing read does not keep the other gauges from being refreshed. */
    public void refresh() {
        try {
            value = read.get().doubleValue();
        } catch (RuntimeException e) {
            value = Double.NaN;
            log.warn("Could not read the gauge {}: {}", name, e.toString());
        }
    }
}
