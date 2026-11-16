package com.bidtix.benchmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Turns run statistics into a markdown table. */
public final class Report {

    private Report() {
    }

    /**
     * Combines the measured runs of one (strategy, scenario, threads) cell: throughput and latencies are the
     * median over runs; the counts come from the run with the median throughput; oversell, leaks and errors are
     * the worst of all runs, so a single bad run cannot hide.
     */
    public static RunStats aggregate(List<RunStats> runs) {
        List<RunStats> byThroughput = runs.stream().sorted(Comparator.comparingDouble(RunStats::opsPerSec)).toList();
        RunStats middle = byThroughput.get(byThroughput.size() / 2);
        Map<String, Integer> errorKinds = new LinkedHashMap<>();
        runs.forEach(r -> r.errorKinds().forEach((k, v) -> errorKinds.merge(k, v, Integer::sum)));
        List<String> notes = runs.stream().flatMap(r -> r.notes().stream()).distinct().collect(Collectors.toList());
        return new RunStats(middle.strategy(), middle.scenario(), middle.threads(), middle.requests(),
                middle.held(), middle.soldOut(), middle.gaveUp(),
                runs.stream().mapToInt(RunStats::errors).max().orElse(0), middle.retries(),
                runs.stream().mapToLong(RunStats::oversold).max().orElse(0),
                runs.stream().mapToLong(RunStats::leaked).max().orElse(0),
                Stats.median(runs.stream().mapToDouble(RunStats::opsPerSec).toArray()),
                Stats.median(runs.stream().mapToDouble(RunStats::p50Ms).toArray()),
                Stats.median(runs.stream().mapToDouble(RunStats::p95Ms).toArray()),
                Stats.median(runs.stream().mapToDouble(RunStats::p99Ms).toArray()),
                errorKinds, notes);
    }

    public static String markdown(List<RunStats> rows, List<String> notImplemented, String environment) {
        StringBuilder out = new StringBuilder("# Anti-overselling benchmark\n\n");
        out.append(environment).append("\n\n");
        out.append("| strategy | scenario | threads | requests | held | sold out | gave up | errors | retries "
                + "| **oversold** | leaked | ops/s | p50 ms | p95 ms | p99 ms | run s |\n");
        out.append("|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|\n");
        for (RunStats r : rows) {
            // "run s" = how long the load lasted (requests / throughput). Under about a second, start-up effects
            // (thread start, JIT, connection pool) are a large part of the measurement.
            out.append(String.format("| %s | %s | %d | %d | %d | %d | %d | %d | %d | **%d** | %d | %.0f | %.2f | %.2f | %.2f | %.2f |%n",
                    r.strategy(), r.scenario(), r.threads(), r.requests(), r.held(), r.soldOut(), r.gaveUp(),
                    r.errors(), r.retries(), r.oversold(), r.leaked(), r.opsPerSec(), r.p50Ms(), r.p95Ms(), r.p99Ms(),
                    r.requests() / r.opsPerSec()));
        }
        List<String> errorLines = new ArrayList<>();
        for (RunStats r : rows) {
            r.errorKinds().forEach((kind, count) ->
                    errorLines.add("- " + r.strategy() + " / " + r.scenario() + " / " + r.threads() + " threads: "
                            + count + " x " + kind));
        }
        if (!errorLines.isEmpty()) {
            out.append("\n## Errors\n\n").append(String.join("\n", errorLines)).append("\n");
        }
        List<String> noteLines = rows.stream().flatMap(r -> r.notes().stream().map(n ->
                "- " + r.strategy() + " / " + r.scenario() + " / " + r.threads() + " threads: " + n)).toList();
        if (!noteLines.isEmpty()) {
            out.append("\n## Notes\n\n").append(String.join("\n", noteLines)).append("\n");
        }
        if (!notImplemented.isEmpty()) {
            out.append("\n## Not implemented yet\n\n");
            notImplemented.forEach(n -> out.append("- ").append(n).append("\n"));
        }
        out.append("\n_Absolute numbers come from PostgreSQL in Docker on one machine; only the relative order of the "
                + "strategies means something. **oversold** must be 0 for every strategy except `naive`._\n");
        return out.toString();
    }
}
