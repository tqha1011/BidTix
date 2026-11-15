package com.bidtix.benchmark.strategies;

import com.bidtix.benchmark.*;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.List;

/**
 * Redis as a GATE, PostgreSQL as the truth (decided for this project, see CLAUDE.md): a Lua script decides
 * atomically in Redis whether a request may go on; only accepted requests reach PostgreSQL, where a conditional
 * UPDATE still decides for real. When the database says no, the gate must be given the stock back.
 *
 * <p><b>TODO(you)</b>
 * <ul>
 *   <li>{@link #beforeRun}: warm Redis from the database. GA: {@code SET bench:zone:<id>:available <capacity>}
 *       (see {@code RedisSupport.zoneKey}). Seats need no warm-up if you gate with per-seat keys. Call
 *       {@code ctx.redis().flushAll()} first so runs do not see each other's keys.</li>
 *   <li>GA gate script: if {@code GET key >= quantity} then {@code DECRBY key quantity} and return 1, else 0.
 *       It MUST be one script ({@code EVAL}); a GET followed by a DECRBY from Java is the same race again.</li>
 *   <li>Seat gate script: for the seat keys (sorted), if none exists then {@code SET key orderId NX PX ttl} for
 *       all of them and return 1, else 0: all or nothing in one script.</li>
 *   <li>After the gate says yes: run the same conditional UPDATE as {@link ConditionalUpdateStrategy}. If it
 *       fails, run a compensating script that gives the quantity back / deletes the seat keys, and return
 *       SOLD_OUT. If the process dies in between, Redis is short by that much until it is warmed again;
 *       say in your ADR how that is repaired.</li>
 *   <li>Gate says no: return SOLD_OUT without touching PostgreSQL. That shortcut is the whole point; the
 *       "ga-soldout-tail" scenario shows it.</li>
 *   <li>Redis may be wiped at any moment ({@code RedisGateCrashTest}): the database must still prevent oversell.</li>
 * </ul>
 */
public class RedisLuaGateStrategy implements HoldStrategy {

    private final StrategyContext ctx;

    /** KEYS[1] = zone key, ARGV[1] = quantity. 1 = taken, 0 = not enough. */
    private static final String GA_GATE = """
        local cur = tonumber(redis.call('GET', KEYS[1]))
        if cur ~= nil and cur >= tonumber(ARGV[1]) then
            redis.call('DECRBY', KEYS[1], ARGV[1])
            return 1
        else
            return 0
        end;
    """;

    /** KEYS[1] = zone key, ARGV[1] = quantity. Gives the quantity back after the database said no. */
    private static final String GA_RELEASE = """
        if redis.call('EXISTS', KEYS[1]) == 1 then
            redis.call('INCRBY', KEYS[1], ARGV[1])
        end
        return 1
    """;

    /** KEYS = seat keys (sorted), ARGV[1] = orderId, ARGV[2] = ttl in ms. All or nothing. */
    private static final String SEAT_GATE = """
        for i = 1, #KEYS do
            if redis.call('EXISTS', KEYS[i]) == 1 then
                return 0
            end
        end
        for i = 1, #KEYS do
            redis.call('SET', KEYS[i], ARGV[1], 'PX', ARGV[2])
        end
        return 1
    """;

    /** KEYS = seat keys, ARGV[1] = orderId. Deletes only the keys that are still ours. */
    private static final String SEAT_RELEASE = """
        for i = 1, #KEYS do
            if redis.call('GET', KEYS[i]) == ARGV[1] then
                redis.call('DEL', KEYS[i])
            end
        end
        return 1
    """;

    public RedisLuaGateStrategy(StrategyContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "redis-lua-gate";
    }

    @Override
    public void beforeRun(RunInfo info) {
        ctx.redis().flushAll();

        if(info.kind() == RunInfo.Kind.GENERAL_ADMISSION) {
            ctx.redis().commands().set(RedisSupport.zoneKey(info.zoneId()), String.valueOf(info.capacity()));
        }
    }

    @Override
    public HoldResult holdSeats(SeatHoldRequest request) {
        RedisCommands<String, String> redis = ctx.redis().commands();
        // ascending ids: the database then locks overlapping requests in the same order, so no deadlock
        List<Long> sorted = request.seatIds().stream().sorted().toList();
        String[] keys = sorted.stream().map(RedisSupport::seatKey).toArray(String[]::new);
        String orderId = String.valueOf(request.orderId());
        // PX wants a duration (ms from now), not a point in time
        long ttlMs = Math.max(1, Duration.between(ctx.clock().instant(), request.expiresAt()).toMillis());

        Long gate = redis.eval(SEAT_GATE, ScriptOutputType.INTEGER, keys, orderId, String.valueOf(ttlMs));
        if (gate == null || gate == 0L) {
            return HoldResult.soldOut();   // gate says no: PostgreSQL is not touched
        }

        HoldResult result = null;
        try {
            result = ctx.tx().execute(status -> {
                int held = ctx.seats().updateSeatHoldBySeatIds(sorted, request.orderId(), request.expiresAt());
                if (held == sorted.size()) {
                    return HoldResult.held();
                }
                status.setRollbackOnly();   // all or nothing
                return HoldResult.soldOut();
            });
            return result;
        } finally {
            // Not held, or the database threw: the keys must not keep blocking these seats.
            if (result == null || result.outcome() != HoldResult.Outcome.HELD) {
                redis.eval(SEAT_RELEASE, ScriptOutputType.INTEGER, keys, orderId);
            }
        }
    }

    @Override
    public HoldResult takeQuantity(QuantityRequest request) {
        RedisCommands<String, String> redis = ctx.redis().commands();
        String[] keys = {RedisSupport.zoneKey(request.zoneId())};
        String quantity = String.valueOf(request.quantity());

        Long gate = redis.eval(GA_GATE, ScriptOutputType.INTEGER, keys, quantity);
        if (gate == null || gate == 0L) {
            return HoldResult.soldOut();   // gate says no: PostgreSQL is not touched
        }

        boolean held = false;
        try {
            Integer rows = ctx.tx().execute(status -> ctx.zones().takeQuantity(request.zoneId(), request.quantity()));
            held = rows != null && rows == 1;
            return held ? HoldResult.held() : HoldResult.soldOut();
        } finally {
            // The database said no, or threw: give the quantity back to the gate.
            if (!held) {
                redis.eval(GA_RELEASE, ScriptOutputType.INTEGER, keys, quantity);
            }
        }
    }
}
