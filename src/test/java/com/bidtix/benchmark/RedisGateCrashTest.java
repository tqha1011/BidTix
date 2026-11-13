package com.bidtix.benchmark;

import com.bidtix.benchmark.RunInfo.Kind;
import com.bidtix.benchmark.strategies.RedisLuaGateStrategy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis is only a gate: wiping it in the middle of a run (restart, eviction, failover) may reject requests
 * wrongly or let more of them through to PostgreSQL, but it must never make PostgreSQL oversell. Skipped until
 * {@link RedisLuaGateStrategy} is implemented.
 */
@Tag("benchmark")
class RedisGateCrashTest extends BenchmarkTestBase {

    @Test
    void flushingRedisDuringTheRun_neverOversellsInPostgres() throws Exception {
        StrategyContext ctx = ctx();
        RedisLuaGateStrategy strategy = new RedisLuaGateStrategy(ctx);
        BenchmarkRunner runner = new BenchmarkRunner(ctx);
        Assumptions.assumeTrue(runner.implemented(strategy, Kind.GENERAL_ADMISSION),
                "RedisLuaGateStrategy is not implemented yet");

        AtomicBoolean running = new AtomicBoolean(true);
        Thread flusher = new Thread(() -> {
            try {
                Thread.sleep(20);
                while (running.get()) {
                    ctx.redis().flushAll();
                    Thread.sleep(15);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        flusher.start();
        try {
            RunStats stats = runner.runOnce(strategy,
                    new Scenario("crash-ga", Kind.GENERAL_ADMISSION, 200, 2000, 1, 3), 32, 1);
            assertThat(stats.oversold()).as("oversold while Redis keeps losing its data").isZero();
        } finally {
            running.set(false);
            flusher.join();
        }
    }
}
