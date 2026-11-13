package com.bidtix.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the jobs on a fixed number of threads that all start at the same instant and keep pulling the next job
 * until none is left. Same idea as the latch pattern in {@code BookingConcurrencyTest}, plus timing.
 */
public final class LoadRunner {

    /** Result and latency of one job; the list returned by {@link #run} is in the same order as the jobs. */
    public record Sample(HoldResult result, long nanos) {
    }

    /** @param errorKinds exception class + message -> how many times it happened (to see deadlocks, timeouts, ...) */
    public record Run(List<Sample> samples, long wallNanos, Map<String, Integer> errorKinds) {
    }

    private LoadRunner() {
    }

    /**
     * An {@link UnsupportedOperationException} from a job means "strategy not implemented": it is rethrown after
     * the run so the caller can skip the strategy. Any other exception becomes {@link HoldResult#error()}.
     */
    public static Run run(List<Callable<HoldResult>> jobs, int threads) throws Exception {
        Sample[] samples = new Sample[jobs.size()];
        Map<String, AtomicInteger> errors = new ConcurrentHashMap<>();
        AtomicInteger next = new AtomicInteger();
        UnsupportedOperationException[] unsupported = new UnsupportedOperationException[1];
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                workers.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    int i;
                    while ((i = next.getAndIncrement()) < jobs.size()) {
                        long start = System.nanoTime();
                        HoldResult result;
                        try {
                            result = jobs.get(i).call();
                        } catch (UnsupportedOperationException e) {
                            unsupported[0] = e;
                            result = HoldResult.error();
                        } catch (Throwable e) {
                            errors.computeIfAbsent(e.getClass().getSimpleName() + ": " + firstLine(e), k -> new AtomicInteger())
                                    .incrementAndGet();
                            result = HoldResult.error();
                        }
                        samples[i] = new Sample(result, System.nanoTime() - start);
                    }
                    return null;
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("benchmark threads did not start");
            }
            long start = System.nanoTime();
            go.countDown();
            for (Future<?> worker : workers) {
                worker.get(10, TimeUnit.MINUTES);
            }
            long wall = System.nanoTime() - start;

            if (unsupported[0] != null) {
                throw unsupported[0];
            }
            Map<String, Integer> kinds = new java.util.TreeMap<>();
            errors.forEach((k, v) -> kinds.put(k, v.get()));
            return new Run(List.of(samples), wall, kinds);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String firstLine(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return "";
        }
        int end = message.indexOf('\n');
        String line = end < 0 ? message : message.substring(0, end);
        return line.length() > 120 ? line.substring(0, 120) : line;
    }
}
