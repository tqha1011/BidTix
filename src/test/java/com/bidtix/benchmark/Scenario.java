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
