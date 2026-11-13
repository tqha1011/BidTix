package com.bidtix.benchmark;

import com.bidtix.benchmark.InvariantChecker.Claim;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests of the benchmark tooling itself, without a database: if these lie, every benchmark number lies. */
class HarnessSelfTest {

    @Test
    void percentile_isNearestRank() {
        long[] values = {50, 10, 40, 20, 30};

        assertThat(Stats.percentile(values, 50)).isEqualTo(30);
        assertThat(Stats.percentile(values, 95)).isEqualTo(50);
        assertThat(Stats.percentile(values, 20)).isEqualTo(10);
        assertThat(Stats.percentile(new long[0], 99)).isZero();
        assertThat(Stats.median(new double[]{5, 1, 3})).isEqualTo(3);
        assertThat(Stats.median(new double[]{4, 1, 3, 2})).isEqualTo(2.5);
    }

    @Test
    void quantityCheck_flagsLostUpdatesAndLeaks() {
        // clients were promised 7, the database recorded 7 taken
        assertThat(InvariantChecker.checkQuantity(10, 3, 7).clean()).isTrue();
        // promised 9 but only 7 recorded: two sold tickets the database does not know about
        assertThat(InvariantChecker.checkQuantity(10, 3, 9).oversold()).isEqualTo(2);
        // promised more than the capacity
        assertThat(InvariantChecker.checkQuantity(10, 0, 12).oversold()).isEqualTo(2);
        // database says 7 taken but only 5 promised: 2 units burnt
        var leaky = InvariantChecker.checkQuantity(10, 3, 5);
        assertThat(leaky.oversold()).isZero();
        assertThat(leaky.leaked()).isEqualTo(2);
        // a counter outside 0..capacity is always reported
        assertThat(InvariantChecker.checkQuantity(10, -1, 11).notes()).isNotEmpty();
    }

    @Test
    void seatCheck_flagsSeatsSoldTwiceOverwrittenAndLeaked() {
        Map<Long, Long> clean = new java.util.HashMap<>(Map.of(1L, 100L, 2L, 100L, 3L, 200L));
        clean.put(4L, null);
        assertThat(InvariantChecker.checkSeats(clean,
                List.of(new Claim(100, List.of(1L, 2L)), new Claim(200, List.of(3L)))).clean()).isTrue();

        // seat 1 was claimed by both winners but the database holds it for 200: sold twice
        Map<Long, Long> overwritten = new java.util.HashMap<>(Map.of(1L, 200L));
        var twice = InvariantChecker.checkSeats(overwritten,
                List.of(new Claim(100, List.of(1L)), new Claim(200, List.of(1L))));
        assertThat(twice.oversold()).isEqualTo(1);

        // winner says it holds seat 5, the database says the seat is free
        Map<Long, Long> free = new java.util.HashMap<>();
        free.put(5L, null);
        assertThat(InvariantChecker.checkSeats(free, List.of(new Claim(100, List.of(5L)))).oversold()).isEqualTo(1);

        // seat 9 is held in the database by an order nobody was promised
        assertThat(InvariantChecker.checkSeats(new java.util.HashMap<>(Map.of(9L, 300L)), List.of()).leaked()).isEqualTo(1);
    }

    @Test
    void loadRunner_exposesARacyCounter_andAcceptsAnAtomicOne() throws Exception {
        int capacity = 10;
        int requests = 400;

        // racy: check, pause, write back a stale value (the classic lost update)
        int[] racyStock = {capacity};
        List<Callable<HoldResult>> racy = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            racy.add(() -> {
                int seen = racyStock[0];
                if (seen < 1) {
                    return HoldResult.soldOut();
                }
                Thread.sleep(1);
                racyStock[0] = seen - 1;
                return HoldResult.held();
            });
        }
        var racyRun = LoadRunner.run(racy, 16);
        long racyPromised = racyRun.samples().stream().filter(s -> s.result().outcome() == HoldResult.Outcome.HELD).count();
        var racyVerdict = InvariantChecker.checkQuantity(capacity, Math.max(racyStock[0], 0), racyPromised);
        assertThat(racyVerdict.oversold()).as("the racy fake must be caught").isPositive();

        // atomic: the decrement is the check
        AtomicInteger stock = new AtomicInteger(capacity);
        List<Callable<HoldResult>> atomic = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            atomic.add(() -> stock.getAndUpdate(v -> v > 0 ? v - 1 : v) > 0 ? HoldResult.held() : HoldResult.soldOut());
        }
        var atomicRun = LoadRunner.run(atomic, 16);
        long atomicPromised = atomicRun.samples().stream().filter(s -> s.result().outcome() == HoldResult.Outcome.HELD).count();
        assertThat(InvariantChecker.checkQuantity(capacity, stock.get(), atomicPromised).clean()).isTrue();
        assertThat(atomicPromised).isEqualTo(capacity);
        assertThat(atomicRun.samples()).hasSize(requests).allSatisfy(s -> assertThat(s.nanos()).isNotNegative());
    }

    @Test
    void loadRunner_countsErrors_andRethrowsNotImplemented() throws Exception {
        List<Callable<HoldResult>> failing = List.of(
                () -> {
                    throw new IllegalStateException("deadlock detected\nsecond line");
                },
                HoldResult::held);
        var run = LoadRunner.run(failing, 2);
        assertThat(run.samples().get(0).result().outcome()).isEqualTo(HoldResult.Outcome.ERROR);
        assertThat(run.errorKinds()).containsEntry("IllegalStateException: deadlock detected", 1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LoadRunner.run(List.of(() -> {
            throw new UnsupportedOperationException("TODO");
        }), 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void report_aggregatesWorstCaseSafetyNumbers_andFormatsATable() {
        RunStats fast = stats("naive", 0, 0, 900);
        RunStats mid = stats("naive", 3, 0, 700);
        RunStats slow = stats("naive", 0, 2, 500);

        RunStats cell = Report.aggregate(List.of(fast, mid, slow));

        assertThat(cell.opsPerSec()).as("median throughput").isEqualTo(700);
        assertThat(cell.oversold()).as("worst oversold").isEqualTo(3);
        assertThat(cell.leaked()).as("worst leaked").isEqualTo(2);

        String table = Report.markdown(List.of(cell), List.of("optimistic / SEATED"), "- env");
        assertThat(table).contains("| naive | seat-hot | 8 |", "**3**", "optimistic / SEATED", "must be 0");
    }

    private static RunStats stats(String strategy, long oversold, long leaked, double ops) {
        return new RunStats(strategy, "seat-hot", 8, 100, 10, 90, 0, 0, 0, oversold, leaked, ops, 1, 2, 3, Map.of(), List.of());
    }
}
