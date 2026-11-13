package com.bidtix.benchmark.strategies;

import com.bidtix.benchmark.HoldResult;
import com.bidtix.benchmark.HoldStrategy;
import com.bidtix.benchmark.QuantityRequest;
import com.bidtix.benchmark.SeatHoldRequest;
import com.bidtix.benchmark.StrategyContext;

/**
 * Pessimistic locking: lock the rows first ({@code SELECT ... FOR UPDATE}), then check and write while nobody
 * else can touch them.
 *
 * <p><b>TODO(you)</b>
 * <ul>
 *   <li>Seats: lock the requested seats in <b>ascending id order</b> (otherwise two overlapping requests
 *       deadlock), check they are all AVAILABLE, then write HELD. Either
 *       {@code em.find(Seat.class, id, LockModeType.PESSIMISTIC_WRITE)} per id, or one native
 *       {@code SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE}.</li>
 *   <li>GA: lock the zone row, check {@code available_quantity >= quantity}, write the new value. Every buyer
 *       queues on this one row for the whole transaction: watch the p95/p99 latency.</li>
 *   <li>Optional variants worth a second row in the report: {@code NOWAIT} (fail fast instead of queueing) or
 *       a lock timeout ({@code jakarta.persistence.lock.timeout}). Return {@code HoldResult.error()} or
 *       {@code gaveUp(0)} when a lock is refused, and say which in your ADR.</li>
 *   <li>A deadlock shows up as an error kind in the report's "Errors" section.</li>
 * </ul>
 */
public class PessimisticStrategy implements HoldStrategy {

    private final StrategyContext ctx;

    public PessimisticStrategy(StrategyContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "pessimistic";
    }

    @Override
    public HoldResult holdSeats(SeatHoldRequest request) {
        throw new UnsupportedOperationException("TODO(you): pessimistic seat hold");
    }

    @Override
    public HoldResult takeQuantity(QuantityRequest request) {
        throw new UnsupportedOperationException("TODO(you): pessimistic quantity take");
    }
}
