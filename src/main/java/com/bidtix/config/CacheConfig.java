package com.bidtix.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Turns on {@code @Cacheable} / {@code @CacheEvict}. The {@code RedisCacheManager} itself is built by Spring Boot
 * from {@code spring.data.redis.*} and {@code spring.cache.redis.*} in application.yaml (TTL, key prefix).
 *
 * <p>Rules (CLAUDE.md): cache-aside with explicit TTLs, evict on write, and the cached value is only a hint for
 * reads; every purchase or bid is validated against PostgreSQL.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
