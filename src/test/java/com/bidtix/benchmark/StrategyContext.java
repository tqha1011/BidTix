package com.bidtix.benchmark;

import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Everything a strategy may use. The beans are the application's real ones, so a strategy can mix JPA, the
 * existing repositories and plain SQL. {@link #redis()} starts a Redis container the first time it is called,
 * so strategies that do not use Redis never pay for it.
 */
public record StrategyContext(TransactionTemplate tx,
                              EntityManager em,
                              JdbcTemplate jdbc,
                              SeatRepository seats,
                              TicketZoneRepository zones,
                              Clock clock) {

    public RedisSupport redis() {
        return RedisSupport.instance();
    }
}
