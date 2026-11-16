package com.bidtix.benchmark;

import com.bidtix.benchmark.RunInfo.Kind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The benchmark: every strategy x scenario x thread count, printed as a markdown table and written to
 * {@code target/benchmark/results-platform.md} (or {@code results-virtual.md} with {@code VIRTUAL=true}). Slow, so it
 * is tagged and only runs with {@code make bench}.
 * <ul>
 *   <li>{@code make bench ONLY=optimistic}: only strategies whose name contains the text</li>
 *   <li>{@code make bench RUNS=3}: measured runs per cell (default 5, plus one discarded warm-up run)</li>
 * </ul>
 * A strategy that still throws {@link UnsupportedOperationException} is listed as "not implemented", not failed.
 * Every other strategy fails the test when the database shows oversell or leaked stock, except the ones that are
 * {@link HoldStrategy#unsafeByDesign() unsafe by design} (the naive baseline).
 */
@Tag("benchmark")
class StrategyBenchmarkTest extends BenchmarkTestBase {

    @Test
    void compareStrategies() throws Exception {
        String only = System.getProperty("bench.only", "");
        int runs = Integer.getInteger("bench.runs", 5);
        int[] threadCounts = Arrays.stream(System.getProperty("bench.threads", "8,32,64").split(","))
                .map(String::trim).mapToInt(Integer::parseInt).toArray();
        int scale = Integer.getInteger("bench.scale", 1);
        boolean virtual = Boolean.getBoolean("bench.virtual");
        // -Dbench.ratios=0.1,0.5,1,2,10 runs the stock sweep (stock / demand) instead of the four fixed scenarios
        String ratioProperty = System.getProperty("bench.ratios", "");
        boolean sweep = !ratioProperty.isBlank();
        int sweepRequests = Integer.getInteger("bench.requests", 20_000);
        List<Scenario> scenarios = sweep
                ? Scenario.sweep(sweepRequests, Arrays.stream(ratioProperty.split(","))
                        .map(String::trim).mapToDouble(Double::parseDouble).toArray())
                : Scenario.ALL.stream().map(s -> s.scaled(scale)).toList();

        StrategyContext ctx = ctx();
        BenchmarkRunner runner = new BenchmarkRunner(ctx, virtual);
        List<RunStats> rows = new ArrayList<>();
        List<String> notImplemented = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (HoldStrategy strategy : Strategies.all(ctx)) {
            if (!strategy.name().contains(only)) {
                continue;
            }
            for (Kind kind : Kind.values()) {
                if (!runner.implemented(strategy, kind)) {
                    notImplemented.add(strategy.name() + " / " + kind);
                    continue;
                }
                for (Scenario scenario : scenarios) {
                    if (scenario.kind() != kind) {
                        continue;
                    }
                    for (int threads : threadCounts) {
                        RunStats cell = runner.measure(strategy, scenario, threads, runs);
                        rows.add(cell);
                        if (!strategy.unsafeByDesign() && (cell.oversold() > 0 || cell.leaked() > 0)) {
                            failures.add(strategy.name() + " / " + scenario.name() + " / " + threads + " threads: oversold="
                                    + cell.oversold() + " leaked=" + cell.leaked());
                        }
                    }
                }
            }
        }

        String shape = sweep
                ? "stock sweep, stock/demand = " + ratioProperty + ", " + sweepRequests + " requests per cell"
                : "fixed scenarios, requests and inventory x" + scale + " (seat-hot keeps 1 seat)";
        String report = Report.markdown(rows, notImplemented, environment(runs, shape, virtual));
        System.out.println(report);
        // one file per kind of run, so running several does not overwrite the others
        Path out = Path.of("target", "benchmark",
                "results-" + (virtual ? "virtual" : "platform") + (sweep ? "-sweep" : "") + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);

        assertThat(failures).as("strategies that must never oversell").isEmpty();
    }

    private String environment(int runs, String shape, boolean virtual) {
        String postgres = jdbc.queryForObject("select version()", String.class);
        return "- PostgreSQL: " + postgres.split(",")[0] + " (Testcontainers)\n"
                + "- Simulated users run on: " + (virtual ? "virtual threads" : "platform threads")
                + "; " + shape + "\n"
                + "- Connection pool: " + POOL_SIZE + ", cores: " + Runtime.getRuntime().availableProcessors()
                + ", JVM: " + System.getProperty("java.version") + "\n"
                + "- Per cell: 1 warm-up run (discarded) + " + runs + " measured runs; throughput and latency are the "
                + "median, oversold/leaked/errors the worst run.";
    }
}
