package com.bidtix.benchmark;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Redis (Testcontainers) shared by the whole benchmark JVM, started on first use. Redis is only a gate in
 * front of PostgreSQL here, so everything in it may be wiped at any time ({@link #flushAll()}).
 * <p>
 * Lettuce connections are thread-safe, so every strategy thread shares {@link #commands()}.
 */
public final class RedisSupport {

    private static RedisSupport instance;

    private final GenericContainer<?> container;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;

    private RedisSupport() {
        container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        container.start();
        client = RedisClient.create("redis://" + container.getHost() + ":" + container.getMappedPort(6379));
        connection = client.connect();
    }

    public static synchronized RedisSupport instance() {
        if (instance == null) {
            instance = new RedisSupport();
        }
        return instance;
    }

    /** The synchronous Lettuce API: {@code eval(script, ScriptOutputType.INTEGER, keys, args...)}, {@code set}, ... */
    public RedisCommands<String, String> commands() {
        return connection.sync();
    }

    /** Wipes everything. Used between runs and by the "Redis lost its data" test. */
    public void flushAll() {
        commands().flushall();
    }

    /** Suggested key for a zone's remaining quantity. */
    public static String zoneKey(long zoneId) {
        return "bench:zone:" + zoneId + ":available";
    }

    /** Suggested key for one seat's hold. */
    public static String seatKey(long seatId) {
        return "bench:seat:" + seatId;
    }
}
