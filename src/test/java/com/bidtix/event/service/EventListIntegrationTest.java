package com.bidtix.event.service;

import com.bidtix.common.dtos.PageResponse;
import com.bidtix.config.CacheConfig;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.event.dtos.CreateEventRequest;
import com.bidtix.event.dtos.CreateVenueRequest;
import com.bidtix.event.dtos.CreateZoneRequest;
import com.bidtix.event.dtos.EventResponse;
import com.bidtix.support.PostgresIntegrationTest;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The database and the cache are shared with the other test classes, so every test builds its own events and only
 * looks at those, and the {@code events} cache is emptied before each test so an old page cannot hide a new event.
 *
 * <p>Spring Data Redis does not make a cache write visible the instant {@code put} returns: a read made microseconds
 * later can still miss, and {@code @CacheEvict(allEntries = true)} ends in {@code Cache.clear()}, which took about
 * 10 ms to take effect when measured ({@code Cache.invalidate()} is immediate). Both are harmless for a cache that
 * is only a hint, but a test must not assume either, so it waits for the state it needs
 * ({@link #awaitCached}, {@link #publishAndWaitForEviction}).
 */
class EventListIntegrationTest extends PostgresIntegrationTest {

    private static final String KEY_PREFIX = "bidtix-backend:events::";

    @Autowired EventService eventService;
    @Autowired VenueService venueService;
    @Autowired ZoneService zoneService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired CacheManager cacheManager;
    @Autowired StringRedisTemplate redis;

    private final Instant now = Instant.now();
    private User organizer;
    private UUID venue;
    private Cache cache;

    @BeforeEach
    void setUp() {
        cache = cacheManager.getCache(CacheConfig.EVENTS);
        cache.invalidate();
        organizer = userRepository.save(new User("organizer-" + UUID.randomUUID() + "@test.dev", "hash", "Org",
                UserRole.ORGANIZER));
        venue = venueService.create(new CreateVenueRequest("List Arena", "1 Street", "Hanoi")).publicId();
    }

    @Test
    void list_hasOnlyPublishedUpcomingEvents_soonestFirst() {
        EventResponse later = published(40);
        EventResponse sooner = published(20);
        EventResponse draft = draft(30);
        EventResponse past = published(-10);
        EventResponse cancelled = published(35);
        jdbc.update("update events set status = 'CANCELLED' where public_id = ?", cancelled.publicId());
        cache.invalidate();

        List<UUID> ids = allListed().stream().map(EventResponse::publicId).toList();

        assertThat(ids).contains(sooner.publicId(), later.publicId());
        assertThat(ids.indexOf(sooner.publicId())).as("soonest first").isLessThan(ids.indexOf(later.publicId()));
        assertThat(ids).doesNotContain(draft.publicId(), past.publicId(), cancelled.publicId());
    }

    @Test
    void list_correctsBadPaging() {
        published(21);
        published(22);

        PageResponse<EventResponse> tooBig = eventService.listPublished(0, 1000);
        PageResponse<EventResponse> negativePage = eventService.listPublished(-5, 1);
        PageResponse<EventResponse> zeroSize = eventService.listPublished(0, 0);

        assertThat(tooBig.size()).isEqualTo(EventService.MAX_PAGE_SIZE);
        assertThat(negativePage.page()).isZero();
        assertThat(zeroSize.size()).isEqualTo(1);
        assertThat(zeroSize.items()).hasSize(1);
    }

    @Test
    void list_pagesDoNotOverlap() {
        published(23);
        published(24);
        published(25);

        PageResponse<EventResponse> first = eventService.listPublished(0, 2);
        PageResponse<EventResponse> second = eventService.listPublished(1, 2);

        assertThat(first.items()).hasSize(2);
        assertThat(first.items()).extracting(EventResponse::publicId)
                .doesNotContainAnyElementsOf(second.items().stream().map(EventResponse::publicId).toList());
    }

    @Test
    void secondRead_isServedFromRedis_untilTheEntryIsGone() {
        EventResponse event = published(26);
        assertThat(listedIds(0, 100)).contains(event.publicId());
        awaitCached("0:100");

        // change the database behind the cache's back: only a cached page can still show the event
        jdbc.update("update events set status = 'CANCELLED' where public_id = ?", event.publicId());
        assertThat(listedIds(0, 100)).as("served from Redis").contains(event.publicId());

        cache.invalidate();
        assertThat(listedIds(0, 100)).as("the truth (PostgreSQL) wins again").doesNotContain(event.publicId());
    }

    @Test
    void publishing_evictsTheCachedPages() {
        EventResponse draft = draft(27);
        assertThat(listedIds(0, 100)).as("fills the cache").doesNotContain(draft.publicId());
        awaitCached("0:100");

        eventService.publish(organizer.getPublicId(), draft.publicId());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(cache.get("0:100")).as("evicted by publish").isNull());
        assertThat(listedIds(0, 100)).contains(draft.publicId());
    }

    @Test
    void cacheKey_usesTheCorrectedPaging_soOneEntryServesBothSpellings() {
        published(28);

        eventService.listPublished(0, 1000);

        awaitCached("0:100");
        assertThat(cache.get("0:1000")).isNull();
    }

    @Test
    void cachedPage_isReadableJson_withKeyPrefixAndTtl() {
        published(29);
        eventService.listPublished(0, 20);

        String key = KEY_PREFIX + "0:20";
        awaitCached("0:20");
        String stored = redis.opsForValue().get(key);

        assertThat(stored).contains("\"items\"", "\"title\"").doesNotContain("EventResponse");
        assertThat(redis.getExpire(key)).as("TTL from spring.cache.redis.time-to-live").isPositive();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private void awaitCached(String key) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(cache.get(key)).as("page " + key + " is cached").isNotNull());
    }

    private List<UUID> listedIds(int page, int size) {
        return eventService.listPublished(page, size).items().stream().map(EventResponse::publicId).toList();
    }

    /** Every listed event, walking all pages (the shared database may hold more than one page of them). */
    private List<EventResponse> allListed() {
        List<EventResponse> all = new ArrayList<>();
        PageResponse<EventResponse> page;
        int number = 0;
        do {
            page = eventService.listPublished(number++, EventService.MAX_PAGE_SIZE);
            all.addAll(page.items());
        } while (number < page.totalPages());
        return all;
    }

    /** A DRAFT event that starts {@code startsInDays} from now (negative = in the past), with one zone. */
    private EventResponse draft(int startsInDays) {
        Instant starts = now.plus(startsInDays, ChronoUnit.DAYS);
        EventResponse event = eventService.createEvent(organizer.getPublicId(), new CreateEventRequest(venue,
                "List-" + UUID.randomUUID(), null, starts, starts.plus(1, ChronoUnit.DAYS),
                starts.minus(20, ChronoUnit.DAYS), starts.minus(1, ChronoUnit.DAYS), null));
        zoneService.createZone(organizer.getPublicId(), event.publicId(),
                new CreateZoneRequest("Standing", ZoneType.GENERAL_ADMISSION, 300_000, 100, null));
        return event;
    }

    private EventResponse published(int startsInDays) {
        return publishAndWaitForEviction(draft(startsInDays));
    }

    /**
     * Publishes, then waits until the eviction that publishing triggers has really run. A marker key is written
     * before the publish; the eviction deletes everything under the cache's prefix, so when the marker is gone the
     * eviction is done and cannot wipe a page that the test caches next.
     */
    private EventResponse publishAndWaitForEviction(EventResponse draft) {
        String marker = KEY_PREFIX + "marker-" + UUID.randomUUID();
        redis.opsForValue().set(marker, "x");

        EventResponse published = eventService.publish(organizer.getPublicId(), draft.publicId());

        await().atMost(Duration.ofSeconds(5)).until(() -> !Boolean.TRUE.equals(redis.hasKey(marker)));
        return published;
    }
}
