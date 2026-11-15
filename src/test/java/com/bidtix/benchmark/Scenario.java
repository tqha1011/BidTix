package com.bidtix.benchmark;

import com.bidtix.benchmark.RunInfo.Kind;

import java.util.List;

/**
 * One load shape. {@code requests} is fixed per scenario (not per thread) so that changing the thread count
 * only changes the concurrency, not the amount of work.
 *
 * @param inventory seats in the zone (SEATED) or the zone capacity (GENERAL_ADMISSION)
 * @param minPerRequest fewest seats / tickets one request asks for
 * @param maxPerRequest most seats / tickets one request asks for
 */
public record Scenario(String name, Kind kind, int inventory, int requests, int minPerRequest, int maxPerRequest) {

    /**
     * The same shape with {@code factor} times the requests, so a run with many threads still has enough work to
     * keep them all busy. The inventory grows by the same factor to keep the ratio of stock to requests, except
     * for a single seat ({@code seat-hot}): its whole point is that everybody fights for ONE seat.
     */
    public Scenario scaled(int factor) {
        if (factor <= 1) {
            return this;
        }
        boolean singleSeat = kind == Kind.SEATED && inventory == 1;
        return new Scenario(name, kind, singleSeat ? inventory : inventory * factor, requests * factor,
                minPerRequest, maxPerRequest);
    }

    public static final List<Scenario> ALL = List.of(
            // one row, everybody wants it: the most contention a single seat can see
            new Scenario("seat-hot", Kind.SEATED, 1, 200, 1, 1),
            // many rows, overlapping sets in random order: contention is spread, deadlocks are possible
            new Scenario("seat-spread", Kind.SEATED, 200, 400, 2, 3),
            // one counter row for everybody: where strategies differ most
            new Scenario("ga-hot", Kind.GENERAL_ADMISSION, 500, 600, 1, 4),
            // far more requests than stock: after the stock is gone every request is a rejection
            new Scenario("ga-soldout-tail", Kind.GENERAL_ADMISSION, 50, 1000, 1, 2));
}
