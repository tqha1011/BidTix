package com.bidtix.event.controller;

import com.bidtix.config.CacheConfig;
import com.bidtix.event.service.EventService;
import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cancelling an event: only a published one at least 3 days before it starts, only by its organizer, and only once. */
class CancelEventApiTest extends ApiTest {

    @Autowired EventService eventService;
    @Autowired CacheManager cacheManager;

    @Test
    void theOrganizerCancelsAPublishedUpcomingEvent() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "PUBLISHED");

        cancel(me, event).andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(event.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(statusOf(event)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select cancelled_at is not null and auctions_cleaned_at is null "
                + "and orders_cleaned_at is null from events where public_id = ?", Boolean.class, event))
                .as("cancelled now, cleaned up later by the jobs").isTrue();
    }

    @Test
    void cancellingEvictsTheCachedPublicList() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "PUBLISHED");
        var cache = cacheManager.getCache(CacheConfig.EVENTS);
        eventService.listPublished(0, 20);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(cache.get("0:20")).isNotNull());

        cancel(me, event).andExpect(status().isOk());

        // shorter than the TTL: an entry that only expires must not pass for an evicted one
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(cache.get("0:20")).isNull());
    }

    @Test
    void anEventThatIsNotPublishedCannotBeCancelled() throws Exception {
        UUID me = user("ORGANIZER");
        for (String state : new String[]{"DRAFT", "CANCELLED", "COMPLETED"}) {
            UUID event = Catalog.event(jdbc, me, state);

            cancel(me, event).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));

            assertThat(statusOf(event)).isEqualTo(state);
        }
    }

    @Test
    void anEventThatHasStartedCannotBeCancelled() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "PUBLISHED");
        jdbc.update("update events set sale_starts_at = now() - interval '3 days', "
                + "sale_ends_at = now() - interval '2 days', starts_at = now() - interval '1 hour' "
                + "where public_id = ?", event);

        cancel(me, event).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));

        assertThat(statusOf(event)).isEqualTo("PUBLISHED");
    }

    @Test
    void anEventThatStartsInLessThan3DaysCannotBeCancelled() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = eventStartingIn(me, "2 days 23 hours");

        cancel(me, event).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));

        assertThat(statusOf(event)).isEqualTo("PUBLISHED");
    }

    @Test
    void anEventThatStartsInMoreThan3DaysCanBeCancelled() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = eventStartingIn(me, "3 days 1 hour");

        cancel(me, event).andExpect(status().isOk());

        assertThat(statusOf(event)).isEqualTo("CANCELLED");
    }

    @Test
    void onlyItsOrganizerMayCancelIt() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");

        cancel(user("ORGANIZER"), event).andExpect(status().isForbidden());
        cancel(user("ORGANIZER"), UUID.randomUUID()).andExpect(status().isNotFound());

        assertThat(statusOf(event)).isEqualTo("PUBLISHED");
    }

    @Test
    void parallelCancels_oneSucceeds_andTheEventChangesOnce() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "PUBLISHED");
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            calls.add(pool.submit(() -> {
                start.await();
                return cancel(me, event).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> call : calls) {
            statuses.add(call.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).containsOnlyOnce(200);
        assertThat(statuses).filteredOn(code -> code == 409).hasSize(callers - 1);
        assertThat(jdbc.queryForObject("select version from events where public_id = ?", Long.class, event)).isEqualTo(1);
    }

    /** A published event starting this far from now, with its sale closed a day before that. */
    private UUID eventStartingIn(UUID organizer, String interval) {
        UUID event = Catalog.event(jdbc, organizer, "PUBLISHED");
        jdbc.update("update events set starts_at = now() + cast(? as interval), "
                + "sale_ends_at = now() + cast(? as interval) - interval '1 day' where public_id = ?",
                interval, interval, event);
        return event;
    }

    private ResultActions cancel(UUID organizer, UUID event) throws Exception {
        return mvc.perform(post("/api/v1/events/{id}/cancel", event).with(as(organizer, "ORGANIZER")));
    }

    private String statusOf(UUID event) {
        return jdbc.queryForObject("select status from events where public_id = ?", String.class, event);
    }
}
