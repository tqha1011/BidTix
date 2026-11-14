package com.bidtix.benchmark.strategies;

import com.bidtix.benchmark.HoldResult;
import com.bidtix.benchmark.HoldStrategy;
import com.bidtix.benchmark.QuantityRequest;
import com.bidtix.benchmark.SeatHoldRequest;
import com.bidtix.benchmark.StrategyContext;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Optimistic locking: read the row together with its {@code version}, decide in Java, then write with
 * {@code WHERE id = ? AND version = ?}. If the row count is 0 somebody changed the row in between, so we roll
 * back and retry in a NEW transaction with fresh data. The retries are bounded and use a little jitter.
 *
 * <p>It is the same mechanism as JPA's {@code @Version}, written in plain SQL so that the benchmark measures
 * the concurrency control and not Hibernate's entity loading and flushing. Say this in the report.
 *
 * <p>Seats: after a lost race the seat is HELD, so the next attempt does not find it AVAILABLE and answers
 * SOLD_OUT cheaply. GA: every buyer touches the same row, so expect many retries; that is the interesting number.
 */
public class OptimisticStrategy implements HoldStrategy {

    private static final int MAX_RETRIES = 8;
    private static final long MAX_JITTER_MS = 20;

    private record SeatRow(long id, long version) {
    }

    private record ZoneRow(int availableQuantity, long version) {
    }

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
        // ascending ids: the batch then updates rows in the same order for every request
        String ids = request.seatIds().stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        int wanted = request.seatIds().size();

        for (int retries = 0; retries <= MAX_RETRIES; retries++) {
            final int r = retries;
            // null from the transaction means "lost the race, try again"
            HoldResult result = ctx.tx().execute(status -> {
                // Only AVAILABLE seats: a seat that is already HELD still has a matching version, so the
                // version check alone would not protect it.
                List<SeatRow> rows = ctx.jdbc().query(
                        "select id, version from seats where id in (" + ids + ") " +
                                "and status = 'AVAILABLE' and held_by_order_id is null order by id",
                        (rs, i) -> new SeatRow(rs.getLong("id"), rs.getLong("version")));

                if (rows.size() != wanted) {
                    return HoldResult.soldOut(r);
                }

                int[] counts = ctx.jdbc().batchUpdate(
                        "update seats set status = 'HELD', held_by_order_id = ?, hold_expires_at = ?, " +
                                "version = version + 1 where id = ? and version = ?",
                        rows.stream().map(s -> new Object[]{
                                request.orderId(), Timestamp.from(request.expiresAt()), s.id(), s.version()
                        }).toList());

                if (Arrays.stream(counts).allMatch(c -> c == 1)) {
                    return HoldResult.held(r);
                }
                // At least one seat changed under us. Seats this batch DID update must not stay held.
                status.setRollbackOnly();
                return null;
            });

            if (result != null) {
                return result;
            }
            pause();
        }
        return HoldResult.gaveUp(MAX_RETRIES);
    }

    @Override
    public HoldResult takeQuantity(QuantityRequest request) {
        for (int retries = 0; retries <= MAX_RETRIES; retries++) {
            final int r = retries;
            HoldResult result = ctx.tx().execute(status -> {
                ZoneRow zone = ctx.jdbc().queryForObject(
                        "select available_quantity, version from ticket_zones where id = ?",
                        (rs, i) -> new ZoneRow(rs.getInt("available_quantity"), rs.getLong("version")),
                        request.zoneId());

                if (zone.availableQuantity() < request.quantity()) {
                    return HoldResult.soldOut(r);
                }

                int rows = ctx.jdbc().update(
                        "update ticket_zones set available_quantity = ?, version = version + 1 " +
                                "where id = ? and version = ?",
                        zone.availableQuantity() - request.quantity(), request.zoneId(), zone.version());

                if (rows == 1) {
                    return HoldResult.held(r);
                }
                status.setRollbackOnly();
                return null;
            });

            if (result != null) {
                return result;
            }
            pause();
        }
        return HoldResult.gaveUp(MAX_RETRIES);
    }

    /** Random pause so the losers of a race do not all come back at the same instant. */
    private static void pause() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, MAX_JITTER_MS + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
