package com.bidtix.benchmark;

import java.util.List;
import java.util.Map;

/** Everything measured in one run (or, after {@link Report#aggregate}, in a set of runs). */
public record RunStats(String strategy, String scenario, int threads, int requests,
                       int held, int soldOut, int gaveUp, int errors, long retries,
                       long oversold, long leaked,
                       double opsPerSec, double p50Ms, double p95Ms, double p99Ms,
                       Map<String, Integer> errorKinds, List<String> notes) {
}
