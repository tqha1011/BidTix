package com.bidtix.benchmark;

import com.bidtix.benchmark.InvariantChecker.Claim;
import com.bidtix.benchmark.InvariantChecker.Verdict;
import com.bidtix.benchmark.RunInfo.Kind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;

/** Runs one strategy on one scenario: build data, hit it from N threads, judge the database, compute statistics. */
public final class BenchmarkRunner {

    private final StrategyContext ctx;
    private final BenchmarkFixture fixture;
    private final boolean virtualThreads;

    public BenchmarkRunner(StrategyContext ctx) {
        this(ctx, false);
    }

    /** @param virtualThreads run the simulated users on virtual threads instead of platform threads */
    public BenchmarkRunner(StrategyContext ctx, boolean virtualThreads) {
        this.ctx = ctx;
        this.fixture = new BenchmarkFixture(ctx.em(), ctx.tx());
        this.virtualThreads = virtualThreads;
    }

    /** True unless the strategy throws UnsupportedOperationException for this kind of inventory. */
    public boolean implemented(HoldStrategy strategy, Kind kind) {
        try {
            if (kind == Kind.SEATED) {
                var data = fixture.seated(1, 1);
                strategy.beforeRun(new RunInfo(kind, data.zoneId(), 1, data.seatIds()));
                strategy.holdSeats(new SeatHoldRequest(data.zoneId(), data.seatIds(), data.orderIds().get(0), data.expiresAt()));
            } else {
                var data = fixture.standing(1);
                strategy.beforeRun(new RunInfo(kind, data.zoneId(), 1, List.of()));
                strategy.takeQuantity(new QuantityRequest(data.zoneId(), 1));
            }
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        } catch (Exception e) {
            return true;   // it tries to do something; the real runs will show what goes wrong
        }
    }

    /** One discarded warm-up run, then {@code runs} measured runs, combined with {@link Report#aggregate}. */
    public RunStats measure(HoldStrategy strategy, Scenario scenario, int threads, int runs) throws Exception {
        runOnce(strategy, scenario, threads, 0);
        List<RunStats> measured = new ArrayList<>();
        for (int i = 1; i <= runs; i++) {
            measured.add(runOnce(strategy, scenario, threads, i));
        }
        return Report.aggregate(measured);
    }

    public RunStats runOnce(HoldStrategy strategy, Scenario scenario, int threads, int runIndex) throws Exception {
        // same seed for every strategy, so they all get the same requests
        Random random = new Random(scenario.name().hashCode() * 31L + runIndex);
        return scenario.kind() == Kind.SEATED
                ? runSeated(strategy, scenario, threads, random)
                : runStanding(strategy, scenario, threads, random);
    }

    private RunStats runSeated(HoldStrategy strategy, Scenario sc, int threads, Random random) throws Exception {
        var data = fixture.seated(sc.inventory(), sc.requests());
        List<List<Long>> wanted = new ArrayList<>();
        List<Callable<HoldResult>> jobs = new ArrayList<>();
        for (int i = 0; i < sc.requests(); i++) {
            List<Long> seats = new ArrayList<>(data.seatIds());
            Collections.shuffle(seats, random);   // deliberately not sorted
            int count = Math.min(sc.minPerRequest() + random.nextInt(sc.maxPerRequest() - sc.minPerRequest() + 1), seats.size());
            List<Long> pick = List.copyOf(seats.subList(0, count));
            wanted.add(pick);
            var request = new SeatHoldRequest(data.zoneId(), pick, data.orderIds().get(i), data.expiresAt());
            jobs.add(() -> strategy.holdSeats(request));
        }
        strategy.beforeRun(new RunInfo(Kind.SEATED, data.zoneId(), sc.inventory(), data.seatIds()));
        LoadRunner.Run run = LoadRunner.run(jobs, threads, virtualThreads);

        List<Claim> winners = new ArrayList<>();
        for (int i = 0; i < run.samples().size(); i++) {
            if (run.samples().get(i).result().outcome() == HoldResult.Outcome.HELD) {
                winners.add(new Claim(data.orderIds().get(i), wanted.get(i)));
            }
        }
        Verdict verdict = InvariantChecker.checkSeats(InvariantChecker.readHolders(ctx.jdbc(), data.zoneId()), winners);
        return stats(strategy, sc, threads, run, verdict);
    }

    private RunStats runStanding(HoldStrategy strategy, Scenario sc, int threads, Random random) throws Exception {
        var data = fixture.standing(sc.inventory());
        int[] quantities = new int[sc.requests()];
        List<Callable<HoldResult>> jobs = new ArrayList<>();
        for (int i = 0; i < sc.requests(); i++) {
            quantities[i] = sc.minPerRequest() + random.nextInt(sc.maxPerRequest() - sc.minPerRequest() + 1);
            var request = new QuantityRequest(data.zoneId(), quantities[i]);
            jobs.add(() -> strategy.takeQuantity(request));
        }
        strategy.beforeRun(new RunInfo(Kind.GENERAL_ADMISSION, data.zoneId(), sc.inventory(), List.of()));
        LoadRunner.Run run = LoadRunner.run(jobs, threads, virtualThreads);

        long promised = 0;
        for (int i = 0; i < run.samples().size(); i++) {
            if (run.samples().get(i).result().outcome() == HoldResult.Outcome.HELD) {
                promised += quantities[i];
            }
        }
        Verdict verdict = InvariantChecker.checkQuantity(sc.inventory(),
                InvariantChecker.readAvailable(ctx.jdbc(), data.zoneId()), promised);
        return stats(strategy, sc, threads, run, verdict);
    }

    private static RunStats stats(HoldStrategy strategy, Scenario sc, int threads, LoadRunner.Run run, Verdict verdict) {
        int[] counts = new int[HoldResult.Outcome.values().length];
        long retries = 0;
        long[] nanos = new long[run.samples().size()];
        for (int i = 0; i < nanos.length; i++) {
            var sample = run.samples().get(i);
            counts[sample.result().outcome().ordinal()]++;
            retries += sample.result().retries();
            nanos[i] = sample.nanos();
        }
        double seconds = run.wallNanos() / 1e9;
        return new RunStats(strategy.name(), sc.name(), threads, sc.requests(),
                counts[HoldResult.Outcome.HELD.ordinal()], counts[HoldResult.Outcome.SOLD_OUT.ordinal()],
                counts[HoldResult.Outcome.GAVE_UP.ordinal()], counts[HoldResult.Outcome.ERROR.ordinal()], retries,
                verdict.oversold(), verdict.leaked(),
                sc.requests() / seconds,
                Stats.percentile(nanos, 50) / 1e6, Stats.percentile(nanos, 95) / 1e6, Stats.percentile(nanos, 99) / 1e6,
                run.errorKinds(), verdict.notes());
    }
}
