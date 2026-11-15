package com.bidtix.benchmark.strategies;

import com.bidtix.benchmark.HoldResult;
import com.bidtix.benchmark.HoldStrategy;
import com.bidtix.benchmark.QuantityRequest;
import com.bidtix.benchmark.SeatHoldRequest;
import com.bidtix.benchmark.StrategyContext;

import java.util.List;

/**
 * What the application does today (ADR-0001 for seats, ADR-0003 for standing): one UPDATE that checks and
 * changes in the same statement, then look at the row count. There is no gap between "is it free?" and "take
 * it", so there is nothing to race on, and no lock is held while application code runs.
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
        // ascending ids: overlapping requests then lock rows in the same order, so they cannot deadlock
        List<Long> sorted = request.seatIds().stream().sorted().toList();

        return ctx.tx().execute(status -> {
            // UPDATE seats SET status='HELD', ... WHERE id IN (...) AND status='AVAILABLE' AND held_by_order_id IS NULL
            int held = ctx.seats().updateSeatHoldBySeatIds(sorted, request.orderId(), request.expiresAt());
            if (held == sorted.size()) {
                return HoldResult.held();
            }
            // Some seat was already taken. The seats this statement DID get are still marked in this
            // transaction, so roll back: a multi-seat request is all or nothing.
            status.setRollbackOnly();
            return HoldResult.soldOut();
        });
    }

    @Override
    public HoldResult takeQuantity(QuantityRequest request) {
        // UPDATE ticket_zones SET available_quantity = available_quantity - :q WHERE id = :z AND available_quantity >= :q
        int rows = ctx.tx().execute(status -> ctx.zones().takeQuantity(request.zoneId(), request.quantity()));
        return rows == 1 ? HoldResult.held() : HoldResult.soldOut();
    }
}
