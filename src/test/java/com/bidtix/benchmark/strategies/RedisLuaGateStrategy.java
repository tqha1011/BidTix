package com.bidtix.benchmark.strategies;

import com.bidtix.benchmark.HoldResult;
import com.bidtix.benchmark.HoldStrategy;
import com.bidtix.benchmark.QuantityRequest;
import com.bidtix.benchmark.RunInfo;
import com.bidtix.benchmark.SeatHoldRequest;
import com.bidtix.benchmark.StrategyContext;

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

    public RedisLuaGateStrategy(StrategyContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "redis-lua-gate";
    }

    @Override
    public void beforeRun(RunInfo info) {
        throw new UnsupportedOperationException("TODO(you): warm Redis from the database");
    }

    @Override
    public HoldResult holdSeats(SeatHoldRequest request) {
        throw new UnsupportedOperationException("TODO(you): Redis gate + conditional UPDATE for seats");
    }

    @Override
    public HoldResult takeQuantity(QuantityRequest request) {
        throw new UnsupportedOperationException("TODO(you): Redis gate + conditional UPDATE for quantity");
    }
}
