package com.bidtix.benchmark.strategies;

import com.bidtix.benchmark.HoldResult;
import com.bidtix.benchmark.HoldStrategy;
import com.bidtix.benchmark.QuantityRequest;
import com.bidtix.benchmark.SeatHoldRequest;
import com.bidtix.benchmark.StrategyContext;

/**
 * Optimistic locking: load the entity, change it, and let {@code @Version} reject the commit if somebody else
 * changed the row in between. The loser retries.
 *
 * <p><b>TODO(you)</b>
 * <ul>
 *   <li>Load {@code Seat} / {@code TicketZone} through {@code ctx.em()} inside {@code ctx.tx()}. The entities
 *       have no setters (CLAUDE.md), so you need either a small domain method on the entity or an
 *       update through the EntityManager; adding a test-only way is fine, but do not change production
 *       entities just for the benchmark without saying so.</li>
 *   <li>Catch {@code ObjectOptimisticLockingFailureException} (and {@code OptimisticLockException}).
 *       <b>Every retry needs a NEW transaction and fresh entities</b>: a failed flush leaves the persistence
 *       context unusable.</li>
 *   <li>Bound the retries (say 5-10) and add a little random jitter between them. Return
 *       {@code HoldResult.gaveUp(retries)} when the limit is hit, {@code held(retries)} / {@code soldOut(retries)}
 *       otherwise, so the report can show retries and false rejects.</li>
 *   <li>Seats: after a lost race the seat is HELD, so the retry sees it and answers SOLD_OUT cheaply.
 *       GA: every buyer touches the same row, so expect many retries; this is the interesting number.</li>
 *   <li>A multi-seat request must be all-or-nothing: if one seat fails, none stay held.</li>
 * </ul>
 */
public class OptimisticStrategy implements HoldStrategy {

    private final StrategyContext ctx;

    public OptimisticStrategy(StrategyContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "optimistic";
    }

    @Override
    public HoldResult holdSeats(SeatHoldRequest request) {
        throw new UnsupportedOperationException("TODO(you): optimistic seat hold");
    }

    @Override
    public HoldResult takeQuantity(QuantityRequest request) {
        throw new UnsupportedOperationException("TODO(you): optimistic quantity take");
    }
}
