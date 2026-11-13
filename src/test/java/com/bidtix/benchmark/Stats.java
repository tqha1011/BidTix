package com.bidtix.benchmark;

import java.util.Arrays;

public final class Stats {

    private Stats() {
    }

    /** Nearest-rank percentile of the values (they do not need to be sorted); {@code p} in (0, 100]. */
    public static double percentile(long[] values, double p) {
        if (values.length == 0) {
            return 0;
        }
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }

    public static double median(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }
}
