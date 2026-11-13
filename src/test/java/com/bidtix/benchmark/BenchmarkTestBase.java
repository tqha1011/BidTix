package com.bidtix.benchmark;

import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import com.bidtix.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Real application, real PostgreSQL. The connection pool is set explicitly (and printed in the report):
 * with the default of 10 connections, 32 or 64 threads would just queue for a connection and every strategy
 * would look the same.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=" + BenchmarkTestBase.POOL_SIZE)
abstract class BenchmarkTestBase extends PostgresIntegrationTest {

    static final int POOL_SIZE = 64;

    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired SeatRepository seats;
    @Autowired TicketZoneRepository zones;
    @Autowired Clock clock;

    StrategyContext ctx() {
        return new StrategyContext(tx, em, jdbc, seats, zones, clock);
    }
}
