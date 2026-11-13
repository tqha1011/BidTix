package com.bidtix.benchmark;

import com.bidtix.benchmark.RunInfo.Kind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The benchmark: every strategy x scenario x thread count, printed as a markdown table and written to
 * {@code target/benchmark/results.md}. Slow, so it is tagged and only runs with {@code make bench}.
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

    private static final int[] THREADS = {8, 32, 64};

    @Test
    void compareStrategies() throws Exception {
        String only = System.getProperty("bench.only", "");
        int runs = Integer.getInteger("bench.runs", 5);

        StrategyContext ctx = ctx();
        BenchmarkRunner runner = new BenchmarkRunner(ctx);
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
                for (Scenario scenario : Scenario.ALL) {
                    if (scenario.kind() != kind) {
                        continue;
                    }
                    for (int threads : THREADS) {
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

        String report = Report.markdown(rows, notImplemented, environment(runs));
        System.out.println(report);
        Path out = Path.of("target", "benchmark", "results.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);

        assertThat(failures).as("strategies that must never oversell").isEmpty();
    }

    private String environment(int runs) {
        String postgres = jdbc.queryForObject("select version()", String.class);
        return "- PostgreSQL: " + postgres.split(",")[0] + " (Testcontainers)\n"
                + "- Connection pool: " + POOL_SIZE + ", cores: " + Runtime.getRuntime().availableProcessors()
                + ", JVM: " + System.getProperty("java.version") + "\n"
                + "- Per cell: 1 warm-up run (discarded) + " + runs + " measured runs; throughput and latency are the "
                + "median, oversold/leaked/errors the worst run.";
    }
}
