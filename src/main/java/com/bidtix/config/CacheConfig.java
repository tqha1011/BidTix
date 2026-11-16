package com.bidtix.config;

import com.bidtix.common.dtos.PageResponse;
import com.bidtix.event.dtos.EventResponse;
import com.bidtix.event.dtos.VenueResponse;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * Turns on {@code @Cacheable} / {@code @CacheEvict}. The {@code RedisCacheManager} itself is built by Spring Boot
 * from {@code spring.data.redis.*} and {@code spring.cache.redis.*} in application.yaml (TTL, key prefix).
 *
 * <p>Rules (CLAUDE.md): cache-aside with explicit TTLs, evict on write, and the cached value is only a hint for
 * reads; every purchase or bid is validated against PostgreSQL.
 *
 * <p>Values are stored as JSON, one typed serializer per cache, so the stored text is readable and carries no
 * class names. Add a cache here with its value type when you introduce it.
 *
 * <p>Caches are transaction aware: a put or an evict made inside a transaction is applied after it commits.
 * Without that, a reader could load the old row between an eviction and the commit and cache it again.
 * Clearing a whole cache ({@code allEntries = true}) scans the cache's keys, which is fine at this size.
 *
 * <p>Spring Data Redis does not make a write visible the instant it returns: a read made microseconds after a
 * {@code put} can still miss, and {@code clear()} took about 10 ms to take effect when measured
 * ({@code invalidate()} is immediate, but a transaction aware cache does not defer it). That is fine for a cache that
 * is only a hint and has a TTL; tests have to wait for the state they need.
 *
 * <p><b>Cache stampede.</b> {@code @Cacheable(sync = true)} makes one request load a missing entry while the others
 * wait for it, but only if the cache writer locks. Spring Data Redis's default writer does not: with it,
 * {@code sync = true} changed nothing (measured: 16 simultaneous requests caused 16 database loads,
 * see {@code CacheStampedeIntegrationTest}). So the manager uses a locking writer. Its lock is a key in Redis, so it
 * works across application instances, and it covers a whole cache: while one entry is being loaded, other reads and
 * writes of that same cache wait too, polling every {@value #LOCK_POLL_MILLIS} ms. The lock expires after
 * {@value #LOCK_TTL_SECONDS} s, so an instance that dies in the middle of a load cannot block the cache for good;
 * a loader slower than that loses the protection. Keep loaders short.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String VENUES = "venues";
    /** Pages of the public list of published events, see {@code EventService#listPublished}. */
    public static final String EVENTS = "events";

    static final long LOCK_POLL_MILLIS = 10;
    static final long LOCK_TTL_SECONDS = 10;

    @Bean
    public RedisCacheManagerBuilderCustomizer cacheSerialization(RedisConnectionFactory connectionFactory) {
        JsonMapper mapper = JsonMapper.builder().build();
        JavaType eventPage = mapper.getTypeFactory().constructParametricType(PageResponse.class, EventResponse.class);

        // cacheDefaults() already carries the TTL and key prefix from application.yaml; only the value format changes
        RedisCacheWriter lockingWriter = RedisCacheWriter.lockingRedisCacheWriter(connectionFactory,
                Duration.ofMillis(LOCK_POLL_MILLIS), RedisCacheWriter.TtlFunction.just(Duration.ofSeconds(LOCK_TTL_SECONDS)),
                BatchStrategies.keys());

        return builder -> builder
                .cacheWriter(lockingWriter)
                .transactionAware()
                .withCacheConfiguration(VENUES, builder.cacheDefaults()
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new JacksonJsonRedisSerializer<>(VenueResponse.class))))
                .withCacheConfiguration(EVENTS, builder.cacheDefaults()
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new JacksonJsonRedisSerializer<>(mapper, eventPage))));
    }
}
