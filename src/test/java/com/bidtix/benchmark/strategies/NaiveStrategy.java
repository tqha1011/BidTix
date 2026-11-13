package com.bidtix.benchmark.strategies;

import com.bidtix.benchmark.HoldResult;
import com.bidtix.benchmark.HoldStrategy;
import com.bidtix.benchmark.QuantityRequest;
import com.bidtix.benchmark.SeatHoldRequest;
import com.bidtix.benchmark.StrategyContext;

/**
 * Baseline: check-then-act with no guard. This is what the code looks like before anyone thinks about
 * concurrency, and it is expected to OVERSELL. The benchmark reports its oversell numbers but does not fail.
 *
 * <p><b>TODO(you)</b>
 * <ul>
 *   <li>Seats: read the seats (are they all AVAILABLE?), and if so write HELD with a plain
 *       {@code UPDATE seats SET status='HELD', held_by_order_id=?, hold_expires_at=? WHERE id IN (...)}
 *       with <i>no</i> {@code status='AVAILABLE'} condition and no row-count check. Remember
 *       {@code ck_seats_hold_consistency}: HELD needs both the owner and the expiry.</li>
 *   <li>GA: read {@code available_quantity}, compare in Java, then write {@code read - quantity}
 *       (a lost update, not a relative decrement).</li>
 *   <li>Do the read and the write in <i>separate</i> transactions, or the default isolation hides part of the
 *       race. That is exactly the mistake this strategy is here to show.</li>
 * </ul>
 */
public class NaiveStrategy implements HoldStrategy {

    private final StrategyContext ctx;

    public NaiveStrategy(StrategyContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "naive";
    }

    @Override
    public boolean unsafeByDesign() {
        return true;
    }

    @Override
    public HoldResult holdSeats(SeatHoldRequest request) {
        throw new UnsupportedOperationException("TODO(you): naive seat hold");
    }

    @Override
    public HoldResult takeQuantity(QuantityRequest request) {
        throw new UnsupportedOperationException("TODO(you): naive quantity take");
    }
}
