package com.bidtix.event.service;

import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.EventStatus;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.event.dtos.CreateEventRequest;
import com.bidtix.event.dtos.CreateVenueRequest;
import com.bidtix.event.dtos.CreateZoneRequest;
import com.bidtix.event.dtos.EventResponse;
import com.bidtix.support.PostgresIntegrationTest;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventPublishIntegrationTest extends PostgresIntegrationTest {

    @Autowired EventService eventService;
    @Autowired VenueService venueService;
    @Autowired ZoneService zoneService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    @Test
    void publish_byTheOwner_movesADraftWithAZoneToPublished() {
        User organizer = organizer();
        EventResponse draft = eventWithZone(organizer);
        Long versionBefore = jdbc.queryForObject("select version from events where public_id = ?", Long.class, draft.publicId());

        EventResponse published = eventService.publish(organizer.getPublicId(), draft.publicId());

        assertThat(published.status()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(published.publicId()).isEqualTo(draft.publicId());
        assertThat(statusInDb(draft.publicId())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("select version from events where public_id = ?", Long.class, draft.publicId()))
                .as("the optimistic-locking version moves with the state change").isEqualTo(versionBefore + 1);
    }

    @Test
    void publish_byAnotherOrganizer_isForbidden_andNothingChanges() {
        EventResponse draft = eventWithZone(organizer());

        assertThatThrownBy(() -> eventService.publish(organizer().getPublicId(), draft.publicId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(statusInDb(draft.publicId())).isEqualTo("DRAFT");
    }

    @Test
    void publish_anEventWithoutZones_isRejected() {
        User organizer = organizer();
        EventResponse draft = eventService.createEvent(organizer.getPublicId(), request(venue()));

        assertThatThrownBy(() -> eventService.publish(organizer.getPublicId(), draft.publicId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EVENT_HAS_NO_ZONES));
        assertThat(statusInDb(draft.publicId())).isEqualTo("DRAFT");
    }

    @Test
    void publish_twice_secondOneIsAConflict() {
        User organizer = organizer();
        EventResponse draft = eventWithZone(organizer);
        eventService.publish(organizer.getPublicId(), draft.publicId());

        assertThatThrownBy(() -> eventService.publish(organizer.getPublicId(), draft.publicId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOT_EDITABLE));
    }

    @Test
    void publish_unknownEvent_isEventNotFound() {
        assertThatThrownBy(() -> eventService.publish(organizer().getPublicId(), UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOTFOUND));
    }

    @Test
    void manyParallelPublishes_exactlyOneWins_theRestAreConflicts() throws Exception {
        User organizer = organizer();
        EventResponse draft = eventWithZone(organizer);
        int threads = 8;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<String> publish = () -> {
                    ready.countDown();
                    go.await();
                    try {
                        eventService.publish(organizer.getPublicId(), draft.publicId());
                        return "PUBLISHED";
                    } catch (BusinessException e) {
                        return e.getErrorCode().name();
                    }
                };
                results.add(pool.submit(publish));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes.stream().filter("PUBLISHED"::equals)).as("exactly one publish wins").hasSize(1);
            assertThat(outcomes.stream().filter(o -> !o.equals("PUBLISHED")))
                    .as("everybody else sees a conflict").hasSize(threads - 1)
                    .containsOnly(ErrorCode.EVENT_NOT_EDITABLE.name());
            assertThat(statusInDb(draft.publicId())).isEqualTo("PUBLISHED");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private String statusInDb(UUID eventPublicId) {
        return jdbc.queryForObject("select status from events where public_id = ?", String.class, eventPublicId);
    }

    private User organizer() {
        return userRepository.save(new User("organizer-" + UUID.randomUUID() + "@test.dev", "hash", "Org",
                UserRole.ORGANIZER));
    }

    private UUID venue() {
        return venueService.create(new CreateVenueRequest("Arena", "1 Street", "Hanoi")).publicId();
    }

    private CreateEventRequest request(UUID venue) {
        return new CreateEventRequest(venue, "Concert", null,
                now.plus(30, ChronoUnit.DAYS), now.plus(31, ChronoUnit.DAYS),
                now.plus(1, ChronoUnit.DAYS), now.plus(29, ChronoUnit.DAYS), null);
    }

    /** A DRAFT event owned by {@code organizer} with one standing zone, so it can be published. */
    private EventResponse eventWithZone(User organizer) {
        EventResponse draft = eventService.createEvent(organizer.getPublicId(), request(venue()));
        zoneService.createZone(organizer.getPublicId(), draft.publicId(),
                new CreateZoneRequest("Standing", ZoneType.GENERAL_ADMISSION, 300_000, 100, null));
        return draft;
    }
}
