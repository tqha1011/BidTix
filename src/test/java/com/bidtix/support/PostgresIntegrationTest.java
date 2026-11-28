package com.bidtix.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests: the full application against a real PostgreSQL in Docker.
 * Flyway builds the schema, so the migrations and the JPA mappings (ddl-auto=validate) are tested too.
 * <p>
 * A real Redis runs next to it (the cache), started with the same password as compose.yaml's default.
 * <p>
 * One container of each is shared by every subclass (started once per JVM, removed by Testcontainers' reaper),
 * so tests must create their own data and never assume an empty database or an empty cache.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            // Every distinct test configuration (@MockitoBean, @TestPropertySource) keeps its own cached Spring
            // context with its own connection pool; the default of 100 connections ran out.
            .withCommand("postgres", "-c", "max_connections=300");

    private static final String REDIS_PASSWORD = "secret";
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        // the refund job calls VNPAY and claims every waiting payment in the shared database: tests call it themselves
        registry.add("bidtix.payment.refund-cron", () -> "-");
        // the same for the job that asks VNPAY about PENDING payments
        registry.add("bidtix.payment.status-query-cron", () -> "-");
        // the key cleanup would delete rows other tests set up as expired: tests call it themselves
        registry.add("bidtix.idempotency.cleanup-cron", () -> "-");
    }
}
