package com.bidtix.benchmark.strategies;

import com.bidtix.benchmark.HoldResult;
import com.bidtix.benchmark.HoldStrategy;
import com.bidtix.benchmark.QuantityRequest;
import com.bidtix.benchmark.SeatHoldRequest;
import com.bidtix.benchmark.StrategyContext;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pessimistic locking: lock the rows first ({@code SELECT ... FOR UPDATE}), then check and write while nobody
 * else can touch them. There is nothing to retry: once the lock is ours the answer is final.
 *
 * <p>Seats: the requested seats are locked in ascending id order, so two overlapping requests cannot
 * deadlock. GA: every buyer queues on the one zone row for the whole transaction, so watch p95/p99 latency.
 *
 * <p>This is the plain "wait for the lock" variant. {@code lock_timeout} only stops a request from waiting
 * forever; a refused lock is not caught here, it reaches the harness and shows up in the report's "Errors"
 * section (as does a deadlock). Variants worth a second row: {@code FOR UPDATE NOWAIT} (fail fast) and
 * {@code SKIP LOCKED}.
 */
public class PessimisticStrategy implements HoldStrategy {

    private static final String LOCK_TIMEOUT = "5s";

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
        String ids = request.seatIds().stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        int wanted = request.seatIds().size();

        return ctx.tx().execute(status -> {
            ctx.jdbc().execute("set local lock_timeout = '" + LOCK_TIMEOUT + "'");

            // Locks are taken in the order the rows come back, hence ORDER BY id. No status filter: lock
            // them all, then look at what we got.
            List<String> statuses = ctx.jdbc().queryForList(
                    "select status from seats where id in (" + ids + ") order by id for update",
                    String.class);

            if (statuses.size() != wanted || statuses.stream().anyMatch(s -> !"AVAILABLE".equals(s))) {
                return HoldResult.soldOut();    // returning commits an empty transaction and frees the locks
            }

            List<Long> sorted = request.seatIds().stream().sorted().toList();
            int[] counts = ctx.jdbc().batchUpdate(
                    "update seats set status = 'HELD', held_by_order_id = ?, hold_expires_at = ?, " +
                            "version = version + 1 where id = ?",
                    sorted.stream().map(id -> new Object[]{
                            request.orderId(), Timestamp.from(request.expiresAt()), id
                    }).toList());

            // The rows are locked, so every update must hit exactly one row. Anything else is a bug.
            if (!Arrays.stream(counts).allMatch(c -> c == 1)) {
                throw new IllegalStateException("locked seats were not all updated: " + Arrays.toString(counts));
            }
            return HoldResult.held();
        });
    }

    @Override
    public HoldResult takeQuantity(QuantityRequest request) {
        return ctx.tx().execute(status -> {
            ctx.jdbc().execute("set local lock_timeout = '" + LOCK_TIMEOUT + "'");

            // Everybody queues on this row until the transaction that holds it commits.
            Integer available = ctx.jdbc().queryForObject(
                    "select available_quantity from ticket_zones where id = ? for update",
                    Integer.class, request.zoneId());

            if (available == null || available < request.quantity()) {
                return HoldResult.soldOut();
            }

            int rows = ctx.jdbc().update(
                    "update ticket_zones set available_quantity = available_quantity - ?, version = version + 1 " +
                            "where id = ?",
                    request.quantity(), request.zoneId());
            if (rows != 1) {
                throw new IllegalStateException("locked zone was not updated: " + rows);
            }
            return HoldResult.held();
        });
    }
}
