package com.bidtix.event.service;

import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Event;
import com.bidtix.event.domain.Venue;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.event.dtos.CreateZoneRequest;
import com.bidtix.event.dtos.CreateZoneRequest.SeatRowRequest;
import com.bidtix.event.dtos.ZoneResponse;
import com.bidtix.support.PostgresIntegrationTest;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

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

class ZoneServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired ZoneService zoneService;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private record Fixture(UUID organizerId, UUID otherOrganizerId, UUID eventId) {
    }

    @Test
    void seatedZone_createsEverySeatDescribedByTheRows() {
        Fixture f = fixture("DRAFT");

        ZoneResponse zone = zoneService.createZone(f.organizerId(), f.eventId(), seated("VIP",
                new SeatRowRequest("A", 1, 20), new SeatRowRequest("B", 5, 24)));

        assertThat(zone.seatCount()).isEqualTo(40);
        assertThat(zone.capacity()).isEqualTo(40);
        assertThat(count("select count(*) from seats s join ticket_zones z on z.id = s.zone_id where z.public_id = ?",
                zone.publicId())).isEqualTo(40);
        assertThat(count("select count(distinct s.public_id) from seats s join ticket_zones z on z.id = s.zone_id "
                + "where z.public_id = ? and s.status = 'AVAILABLE'", zone.publicId())).isEqualTo(40);
        assertThat(count("select count(*) from seats s join ticket_zones z on z.id = s.zone_id "
                + "where z.public_id = ? and s.row_label = 'B' and s.seat_number = 5", zone.publicId())).isEqualTo(1);
    }

    @Test
    void generalAdmissionZone_hasCapacityAsAvailableQuantity_andNoSeats() {
        Fixture f = fixture("DRAFT");

        ZoneResponse zone = zoneService.createZone(f.organizerId(), f.eventId(),
                new CreateZoneRequest("Standing", ZoneType.GENERAL_ADMISSION, 300_000, 500, null));

        assertThat(zone.seatCount()).isZero();
        assertThat(count("select available_quantity from ticket_zones where public_id = ?", zone.publicId()))
                .isEqualTo(500);
        assertThat(count("select count(*) from seats s join ticket_zones z on z.id = s.zone_id where z.public_id = ?",
                zone.publicId())).isZero();
    }

    @Test
    void invalidShapes_areRejected_andNothingIsSaved() {
        Fixture f = fixture("DRAFT");
        List<CreateZoneRequest> bad = List.of(
                seated("no rows"),                                                                   // SEATED without rows
                seated("dup", new SeatRowRequest("A", 1, 5), new SeatRowRequest(" A ", 6, 9)),       // same row twice
                seated("reversed", new SeatRowRequest("A", 9, 3)),                                   // toSeat < fromSeat
                seated("too many", new SeatRowRequest("A", 1, 10_001)),                              // over the cap
                new CreateZoneRequest("cap mismatch", ZoneType.SEATED, 1000, 99, List.of(new SeatRowRequest("A", 1, 5))),
                new CreateZoneRequest("ga rows", ZoneType.GENERAL_ADMISSION, 1000, 10, List.of(new SeatRowRequest("A", 1, 5))),
                new CreateZoneRequest("ga no capacity", ZoneType.GENERAL_ADMISSION, 1000, null, null));

        for (CreateZoneRequest request : bad) {
            assertThatThrownBy(() -> zoneService.createZone(f.organizerId(), f.eventId(), request))
                    .as(request.name())
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
        assertThat(zonesOf(f)).isZero();
    }

    @Test
    void anotherOrganizer_isForbidden() {
        Fixture f = fixture("DRAFT");

        assertThatThrownBy(() -> zoneService.createZone(f.otherOrganizerId(), f.eventId(),
                seated("VIP", new SeatRowRequest("A", 1, 2))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(zonesOf(f)).isZero();
    }

    @Test
    void publishedEvent_cannotGetNewZones() {
        Fixture f = fixture("PUBLISHED");

        assertThatThrownBy(() -> zoneService.createZone(f.organizerId(), f.eventId(),
                seated("VIP", new SeatRowRequest("A", 1, 2))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOT_EDITABLE));
    }

    @Test
    void unknownEvent_isEventNotFound() {
        Fixture f = fixture("DRAFT");

        assertThatThrownBy(() -> zoneService.createZone(f.organizerId(), UUID.randomUUID(),
                seated("VIP", new SeatRowRequest("A", 1, 2))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOTFOUND));
    }

    /**
     * Retries and double clicks send the same zone twice. The unique index on (event_id, name) is the
     * guard: exactly one request may win, the others must see ZONE_NAME_TAKEN, and the losers must not
     * leave seats behind (their whole transaction rolls back).
     */
    @Test
    void sameZoneNameInParallel_exactlyOneWins_andOnlyItsSeatsExist() throws Exception {
        Fixture f = fixture("DRAFT");
        int threads = 12;

        List<Callable<Object>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(() -> zoneService.createZone(f.organizerId(), f.eventId(),
                    seated("Same name", new SeatRowRequest("A", 1, 20))));
        }
        List<Object> outcomes = runInParallel(jobs);

        long wins = outcomes.stream().filter(o -> o instanceof ZoneResponse).count();
        List<Object> failures = outcomes.stream().filter(o -> !(o instanceof ZoneResponse)).toList();
        assertThat(wins).as("successful creates").isEqualTo(1);
        assertThat(failures).as("every loser").allSatisfy(o -> assertThat(o)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZONE_NAME_TAKEN)));
        assertThat(zonesOf(f)).isEqualTo(1);
        assertThat(count("select count(*) from seats s join ticket_zones z on z.id = s.zone_id "
                + "join events e on e.id = z.event_id where e.public_id = ?", f.eventId())).isEqualTo(20);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static CreateZoneRequest seated(String name, SeatRowRequest... rows) {
        return new CreateZoneRequest(name, ZoneType.SEATED, 500_000, null, List.of(rows));
    }

    private int zonesOf(Fixture f) {
        return count("select count(*) from ticket_zones z join events e on e.id = z.event_id where e.public_id = ?",
                f.eventId());
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    /** Returns each job's result or the exception it threw, after releasing all of them at the same moment. */
    private List<Object> runInParallel(List<Callable<Object>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch ready = new CountDownLatch(jobs.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> job : jobs) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return job.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Creates its own organizers, venue and event, so tests do not depend on each other. */
    private Fixture fixture(String eventStatus) {
        String run = UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> {
            User organizer = new User("org-" + run + "@test.dev", "hash", "Organizer", UserRole.ORGANIZER);
            User other = new User("other-" + run + "@test.dev", "hash", "Other", UserRole.ORGANIZER);
            em.persist(organizer);
            em.persist(other);
            Venue venue = new Venue("Arena " + run, "1 Street", "Hanoi");
            em.persist(venue);
            Instant now = Instant.now();
            Event event = new Event(organizer.getId(), venue, "Concert " + run, null,
                    now.plus(30, ChronoUnit.DAYS), now.plus(31, ChronoUnit.DAYS),
                    now.plus(1, ChronoUnit.DAYS), now.plus(29, ChronoUnit.DAYS), 4);
            em.persist(event);
            em.flush();
            if (!eventStatus.equals("DRAFT")) {
                // no domain method publishes an event yet (out of scope), so set the status directly
                em.createNativeQuery("update events set status = :s where id = :id")
                        .setParameter("s", eventStatus).setParameter("id", event.getId()).executeUpdate();
            }
            return new Fixture(organizer.getPublicId(), other.getPublicId(), event.getPublicId());
        });
    }
}
