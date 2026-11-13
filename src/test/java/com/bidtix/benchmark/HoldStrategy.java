package com.bidtix.benchmark;

/**
 * One way of preventing overselling. Implement both methods, run them under load with {@code make bench},
 * and compare. Rules the harness relies on:
 * <ul>
 *   <li>Each call is one customer request. It manages its own transaction(s) (use {@code ctx.tx()}); the
 *       harness calls it from many threads at once, so keep no mutable state in the strategy.</li>
 *   <li>Return {@link HoldResult#held()} only when the stock really is ours in PostgreSQL. PostgreSQL stays
 *       the source of truth; the harness compares your answers with the database afterwards.</li>
 *   <li>Throw {@link UnsupportedOperationException} to say "not implemented yet": the benchmark then reports
 *       that cell as not implemented instead of failing.</li>
 *   <li>Any other exception is counted as {@link HoldResult.Outcome#ERROR}.</li>
 * </ul>
 */
public interface HoldStrategy {

    /** Short name for the report, e.g. "optimistic". */
    String name();

    /** SEATED zone: hold specific seats for an order. All or nothing: a partial hold must not stay behind. */
    HoldResult holdSeats(SeatHoldRequest request);

    /** GENERAL_ADMISSION zone: take a quantity from the zone counter. All or nothing. */
    HoldResult takeQuantity(QuantityRequest request);

    /** Called once before each run, after the data exists and before the load starts. Optional. */
    default void beforeRun(RunInfo info) {
    }

    /**
     * True for a strategy that is expected to oversell (the naive baseline). The benchmark only reports its
     * oversell numbers; for every other strategy it fails the test when oversell is not zero.
     */
    default boolean unsafeByDesign() {
        return false;
    }
}
