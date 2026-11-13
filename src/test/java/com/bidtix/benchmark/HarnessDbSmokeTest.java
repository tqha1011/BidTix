package com.bidtix.benchmark;

import com.bidtix.benchmark.RunInfo.Kind;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness against a real PostgreSQL, with two small strategies written right here (so this test does not
 * depend on the ones you implement): an unguarded read-then-write that MUST be caught overselling, and the
 * conditional UPDATE that MUST pass. This is the "check that a concurrency test can fail" rule from CLAUDE.md,
 * applied to the benchmark itself: if the naive one ever comes out clean, the checker is blind.
 */
class HarnessDbSmokeTest extends BenchmarkTestBase {

    private static final int THREADS = 16;

    private static final Scenario HOT_SEAT = new Scenario("smoke-seat-hot", Kind.SEATED, 1, 64, 1, 1);
    private static final Scenario SPREAD = new Scenario("smoke-seat-spread", Kind.SEATED, 12, 80, 2, 3);
    // far more seats than demand: most seats stay free, which the leak check must not mistake for held seats
    private static final Scenario SPARSE = new Scenario("smoke-seat-sparse", Kind.SEATED, 50, 8, 1, 1);
    private static final Scenario HOT_COUNTER = new Scenario("smoke-ga", Kind.GENERAL_ADMISSION, 20, 120, 1, 3);

    @Test
    void naiveCheckThenAct_isCaughtOverselling() throws Exception {
        BenchmarkRunner runner = new BenchmarkRunner(ctx());
        HoldStrategy naive = new Naive(ctx());

        assertThat(runner.runOnce(naive, HOT_SEAT, THREADS, 1).oversold()).as("hot seat").isPositive();
        assertThat(runner.runOnce(naive, HOT_COUNTER, THREADS, 1).oversold()).as("hot counter").isPositive();
    }

    @Test
    void conditionalUpdate_passesEveryScenario() throws Exception {
        BenchmarkRunner runner = new BenchmarkRunner(ctx());
        HoldStrategy conditional = new Conditional(ctx());

        for (Scenario scenario : List.of(HOT_SEAT, SPREAD, SPARSE, HOT_COUNTER)) {
            RunStats stats = runner.runOnce(conditional, scenario, THREADS, 1);
            assertThat(stats.errors()).as(scenario.name() + " errors " + stats.errorKinds()).isZero();
            assertThat(stats.oversold()).as(scenario.name() + " oversold").isZero();
            assertThat(stats.leaked()).as(scenario.name() + " leaked").isZero();
            assertThat(stats.held()).as(scenario.name() + " winners").isPositive();
        }
    }

    @Test
    void aStrategyThatThrowsNotImplemented_isReportedAsSuch() {
        BenchmarkRunner runner = new BenchmarkRunner(ctx());
        HoldStrategy todo = new HoldStrategy() {
            public String name() { return "todo"; }
            public HoldResult holdSeats(SeatHoldRequest r) { throw new UnsupportedOperationException("TODO"); }
            public HoldResult takeQuantity(QuantityRequest r) { return HoldResult.soldOut(); }
        };

        assertThat(runner.implemented(todo, Kind.SEATED)).isFalse();
        assertThat(runner.implemented(todo, Kind.GENERAL_ADMISSION)).isTrue();
    }

    /** Read the stock in one transaction, think for a moment, write in another: the race, on purpose. */
    private static final class Naive implements HoldStrategy {
        private final StrategyContext ctx;

        Naive(StrategyContext ctx) {
            this.ctx = ctx;
        }

        public String name() {
            return "smoke-naive";
        }

        public HoldResult holdSeats(SeatHoldRequest r) {
            String ids = ids(r.seatIds());
            Integer free = ctx.tx().execute(s -> ctx.jdbc().queryForObject(
                    "select count(*) from seats where status = 'AVAILABLE' and id in (" + ids + ")", Integer.class));
            if (free == null || free != r.seatIds().size()) {
                return HoldResult.soldOut();
            }
            pause();
            ctx.tx().executeWithoutResult(s -> ctx.jdbc().update(
                    "update seats set status = 'HELD', held_by_order_id = ?, hold_expires_at = ? where id in (" + ids + ")",
                    r.orderId(), Timestamp.from(r.expiresAt())));
            return HoldResult.held();
        }

        public HoldResult takeQuantity(QuantityRequest r) {
            Integer available = ctx.tx().execute(s -> ctx.jdbc().queryForObject(
                    "select available_quantity from ticket_zones where id = ?", Integer.class, r.zoneId()));
            if (available == null || available < r.quantity()) {
                return HoldResult.soldOut();
            }
            pause();
            ctx.tx().executeWithoutResult(s -> ctx.jdbc().update(
                    "update ticket_zones set available_quantity = ? where id = ?", available - r.quantity(), r.zoneId()));
            return HoldResult.held();
        }

        private static void pause() {
            try {
                Thread.sleep(3);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The application's own conditional UPDATEs, with the row count checked. */
    private static final class Conditional implements HoldStrategy {
        private final StrategyContext ctx;

        Conditional(StrategyContext ctx) {
            this.ctx = ctx;
        }

        public String name() {
            return "smoke-conditional";
        }

        public HoldResult holdSeats(SeatHoldRequest r) {
            List<Long> sorted = r.seatIds().stream().sorted().toList();
            return ctx.tx().execute(status -> {
                int held = ctx.seats().updateSeatHoldBySeatIds(sorted, r.orderId(), r.expiresAt());
                if (held == sorted.size()) {
                    return HoldResult.held();
                }
                status.setRollbackOnly();   // give back the seats this request did get
                return HoldResult.soldOut();
            });
        }

        public HoldResult takeQuantity(QuantityRequest r) {
            int rows = ctx.tx().execute(status -> ctx.zones().takeQuantity(r.zoneId(), r.quantity()));
            return rows == 1 ? HoldResult.held() : HoldResult.soldOut();
        }
    }

    private static String ids(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
