package com.bidtix.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Reads the application's counters in tests. The registry is shared by a whole context: compare before and after. */
public final class Counters {

    private Counters() {
    }

    /** The counter's value, 0 while it does not exist. */
    public static double value(MeterRegistry registry, String name, String tag, String tagValue) {
        Counter counter = registry.find(name).tag(tag, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }

    public static double jobFailures(MeterRegistry registry, String job) {
        return value(registry, "bidtix.job.item.failures", "job", job);
    }
}
