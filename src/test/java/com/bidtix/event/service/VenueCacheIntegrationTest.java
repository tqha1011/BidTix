package com.bidtix.event.service;

import com.bidtix.common.dtos.PageResponse;
import com.bidtix.config.CacheConfig;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Venue;
import com.bidtix.event.dtos.CreateVenueRequest;
import com.bidtix.event.dtos.VenueResponse;
import com.bidtix.event.infrastructure.VenueRepository;
import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class VenueCacheIntegrationTest extends PostgresIntegrationTest {

    @Autowired VenueService venueService;
    @Autowired VenueRepository venueRepository;
    @Autowired CacheManager cacheManager;
    @Autowired StringRedisTemplate redis;

    private VenueResponse newVenue() {
        return venueService.create(new CreateVenueRequest("Cache Arena " + UUID.randomUUID(), "1 Test Street", "Hanoi"));
    }

    /** A cache write is not visible the instant put returns (see EventListIntegrationTest), so wait for it. */
    private void awaitCached(UUID publicId) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(cacheManager.getCache(CacheConfig.VENUES).get(publicId)).isNotNull());
    }

    @Test
    void secondRead_isServedFromRedis_notFromPostgres() {
        VenueResponse created = newVenue();

        VenueResponse first = venueService.getByPublicId(created.publicId());
        assertThat(first).isEqualTo(created);
        awaitCached(created.publicId());

        // Remove the row behind the cache's back. A read that still works can only come from Redis.
        venueRepository.findByPublicId(created.publicId()).ifPresent(venueRepository::delete);
        assertThat(venueRepository.findByPublicId(created.publicId())).isEmpty();

        assertThat(venueService.getByPublicId(created.publicId())).isEqualTo(created);

        // once the entry is gone the truth (PostgreSQL) wins again. An evict is not visible the instant it returns either
        // (see CacheConfig), so wait until the cache no longer has it; on a slow machine the next read came too soon.
        cacheManager.getCache(CacheConfig.VENUES).evict(created.publicId());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(cacheManager.getCache(CacheConfig.VENUES).get(created.publicId())).isNull());
        assertThatThrownBy(() -> venueService.getByPublicId(created.publicId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VENUE_NOTFOUND));
    }

    @Test
    void entryIsStoredAsReadableJson_withAKeyPrefixAndATtl() {
        VenueResponse created = newVenue();
        venueService.getByPublicId(created.publicId());
        awaitCached(created.publicId());

        String key = "bidtix-backend:venues::" + created.publicId();
        String stored = redis.opsForValue().get(key);

        assertThat(stored).contains("\"name\"", created.name());
        assertThat(stored).as("no class name in the stored value").doesNotContain("VenueResponse");
        Long ttlSeconds = redis.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttlSeconds).as("TTL from spring.cache.redis.time-to-live").isBetween(1L, 10L);
    }

    @Test
    void venueThatDoesNotExist_isNotCached() {
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> venueService.getByPublicId(missing)).isInstanceOf(BusinessException.class);

        Cache cache = cacheManager.getCache(CacheConfig.VENUES);
        assertThat(cache.get(missing)).isNull();
    }

    // ---- the venue list ----

    private static String pageRedisKey(String page) {
        return "bidtix-backend:venue-pages::" + page;
    }

    private void awaitPageCached(String page) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(redis.hasKey(pageRedisKey(page))).as("page %s is cached", page).isTrue());
    }

    /** Shorter than the TTL on purpose: an entry that merely expires must not pass for an evicted one. */
    private void awaitPageEvicted(String page) {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(redis.hasKey(pageRedisKey(page))).as("page %s is evicted", page).isFalse());
    }

    /** Reads page 0 of size 1 into an empty cache and waits for the entry. */
    private PageResponse<VenueResponse> cacheFirstPage() {
        redis.delete(pageRedisKey("0:1"));
        PageResponse<VenueResponse> page = venueService.list(0, 1);
        awaitPageCached("0:1");
        return page;
    }

    @Test
    void secondListRead_isServedFromRedis_notFromPostgres() {
        PageResponse<VenueResponse> first = cacheFirstPage();

        // A row added behind the cache's back: a read that does not count it can only come from Redis.
        venueRepository.save(new Venue("Back Door " + UUID.randomUUID(), "1 Test Street", "Hanoi"));

        assertThat(venueService.list(0, 1)).isEqualTo(first);
    }

    @Test
    void listCacheKey_usesTheCorrectedPaging_soOneEntryServesBothSpellings() {
        redis.delete(pageRedisKey("0:100"));

        venueService.list(-3, 1000);

        awaitPageCached("0:100");
        assertThat(redis.hasKey(pageRedisKey("-3:1000"))).isFalse();
    }

    @Test
    void creatingAVenue_evictsTheCachedList() {
        long before = cacheFirstPage().totalElements();

        newVenue();

        awaitPageEvicted("0:1");
        assertThat(venueService.list(0, 1).totalElements()).isEqualTo(before + 1);
    }

    @Test
    void updatingAVenue_evictsTheCachedList() {
        VenueResponse venue = newVenue();
        cacheFirstPage();

        venueService.update(venue.publicId(),
                new CreateVenueRequest("Renamed " + UUID.randomUUID(), "2 Test Street", "Hue"));

        awaitPageEvicted("0:1");
    }

    @Test
    void deletingAVenue_evictsTheCachedList() {
        VenueResponse venue = newVenue();
        long before = cacheFirstPage().totalElements();

        venueService.delete(venue.publicId());

        awaitPageEvicted("0:1");
        assertThat(venueService.list(0, 1).totalElements()).isEqualTo(before - 1);
    }
}
