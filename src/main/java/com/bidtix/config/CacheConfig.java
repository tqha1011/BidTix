package com.bidtix.config;

import com.bidtix.common.dtos.PageResponse;
import com.bidtix.event.dtos.EventResponse;
import com.bidtix.event.dtos.VenueResponse;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

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
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String VENUES = "venues";
    /** Pages of the public list of published events, see {@code EventService#listPublished}. */
    public static final String EVENTS = "events";

    @Bean
    public RedisCacheManagerBuilderCustomizer cacheSerialization() {
        JsonMapper mapper = JsonMapper.builder().build();
        JavaType eventPage = mapper.getTypeFactory().constructParametricType(PageResponse.class, EventResponse.class);

        // cacheDefaults() already carries the TTL and key prefix from application.yaml; only the value format changes
        return builder -> builder
                .transactionAware()
                .withCacheConfiguration(VENUES, builder.cacheDefaults()
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new JacksonJsonRedisSerializer<>(VenueResponse.class))))
                .withCacheConfiguration(EVENTS, builder.cacheDefaults()
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new JacksonJsonRedisSerializer<>(mapper, eventPage))));
    }
}
