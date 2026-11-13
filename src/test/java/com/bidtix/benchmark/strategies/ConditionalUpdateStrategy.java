package com.bidtix.benchmark.strategies;

import com.bidtix.benchmark.HoldResult;
import com.bidtix.benchmark.HoldStrategy;
import com.bidtix.benchmark.QuantityRequest;
import com.bidtix.benchmark.SeatHoldRequest;
import com.bidtix.benchmark.StrategyContext;

/**
 * What the application does today (ADR-0001 for seats, ADR-0003 for standing): one UPDATE that checks and
 * changes in the same statement, then look at the row count.
 *
 * <p><b>TODO(you)</b>
 * <ul>
 *   <li>Seats: {@code ctx.seats().updateSeatHoldBySeatIds(sortedIds, orderId, expiresAt)} already exists. Sort the
 *       ids ascending first (overlapping requests lock rows in the same order, so no deadlock). Row count equal
 *       to the number of seats = HELD; anything else = SOLD_OUT and the transaction must roll back, otherwise
 *       the seats it did get stay held (use {@code ctx.tx()} and {@code setRollbackOnly()}).</li>
 *   <li>GA: {@code ctx.zones().takeQuantity(zoneId, quantity)} already exists; 1 row = HELD, 0 = SOLD_OUT.</li>
 *   <li>The existing repository methods are annotated {@code @Modifying} and need a transaction: wrap the
 *       call in {@code ctx.tx().execute(...)}.</li>
 * </ul>
 */
public class ConditionalUpdateStrategy implements HoldStrategy {

    private final StrategyContext ctx;

    public ConditionalUpdateStrategy(StrategyContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "conditional-update";
    }

    @Override
    public HoldResult holdSeats(SeatHoldRequest request) {
        throw new UnsupportedOperationException("TODO(you): conditional UPDATE seat hold");
    }

    @Override
    public HoldResult takeQuantity(QuantityRequest request) {
        throw new UnsupportedOperationException("TODO(you): conditional UPDATE quantity take");
    }
}
