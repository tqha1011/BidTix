package com.bidtix.benchmark.strategies;

import com.bidtix.benchmark.HoldResult;
import com.bidtix.benchmark.HoldStrategy;
import com.bidtix.benchmark.QuantityRequest;
import com.bidtix.benchmark.SeatHoldRequest;
import com.bidtix.benchmark.StrategyContext;

import java.sql.Timestamp;
import java.util.stream.Collectors;

/**
 * Baseline: just take the stock and never check anything. No read, no WHERE condition on the stock, no row
 * count. It says HELD to everybody. This is what the code looks like before anyone thinks about concurrency.
 * The benchmark reports its numbers but does not fail it ({@link #unsafeByDesign()}).
 *
 * <p>What to expect: seats are overwritten by whoever writes last, so many clients are told they own the same
 * seat (oversold). The counter loses updates (read, subtract in Java, write back), so more tickets are promised
 * than the database records. Requests that would push the counter below zero are stopped by
 * {@code ck_ticket_zones_available_range} and show up as errors: the database is the last line of defence here,
 * not this strategy being safe.
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
        String ids = request.seatIds().stream().map(String::valueOf).collect(Collectors.joining(","));

        // no "AND status = 'AVAILABLE'", no row count: mark the seats HELD for this order, whatever they were
        ctx.tx().executeWithoutResult(status -> ctx.jdbc().update(
                "update seats set status = 'HELD', held_by_order_id = ?, " +
                        "hold_expires_at = ? where id in (" + ids + ")",
                request.orderId(),
                Timestamp.from(request.expiresAt())));
        return HoldResult.held();
    }

    @Override
    public HoldResult takeQuantity(QuantityRequest request) {
        // Read-modify-write, the way it is written without thinking about concurrency: read the counter,
        // subtract in Java, write the result back. No check, no row count. Two requests that read the same
        // value both write "value - quantity", so one subtraction is lost: that is an oversell. It is not
        // saved by the transaction: in READ COMMITTED the second read does not see the first write yet.
        ctx.tx().executeWithoutResult(status -> {
            Integer available = ctx.jdbc().queryForObject(
                    "select available_quantity from ticket_zones where id = ?", Integer.class, request.zoneId());
            int remaining = available - request.quantity();
            ctx.jdbc().update("update ticket_zones set available_quantity = ? where id = ?", remaining, request.zoneId());
        });
        return HoldResult.held();
    }
}
