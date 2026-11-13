package com.bidtix.benchmark;

import com.bidtix.benchmark.strategies.ConditionalUpdateStrategy;
import com.bidtix.benchmark.strategies.NaiveStrategy;
import com.bidtix.benchmark.strategies.OptimisticStrategy;
import com.bidtix.benchmark.strategies.PessimisticStrategy;
import com.bidtix.benchmark.strategies.RedisLuaGateStrategy;

import java.util.List;

/** The strategies the benchmark compares. Add yours here to include it. */
public final class Strategies {

    private Strategies() {
    }

    public static List<HoldStrategy> all(StrategyContext ctx) {
        return List.of(
                new NaiveStrategy(ctx),
                new ConditionalUpdateStrategy(ctx),
                new OptimisticStrategy(ctx),
                new PessimisticStrategy(ctx),
                new RedisLuaGateStrategy(ctx));
    }
}
