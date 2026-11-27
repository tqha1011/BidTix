package com.bidtix.booking.service;

import com.bidtix.event.domain.Event;
import com.bidtix.event.domain.Seat;
import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.domain.Venue;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Test data for standing-zone bookings. Every call creates its own event, zones and users. */
public final class GeneralAdmissionFixtures {

    /**
     * An event with a GA zone of {@code capacity} tickets, plus: a SEATED zone with 6 seats in the same
     * event, and a GA zone that belongs to a different event.
     */
    public record Fixture(UUID eventId, UUID zoneId, long zoneDbId, long price,
                   UUID seatedZoneId, List<UUID> seatedSeatIds,
                   UUID otherEventZoneId, List<UUID> userIds) {
    }

    private GeneralAdmissionFixtures() {
    }

    public static Fixture create(EntityManager em, TransactionTemplate tx, int capacity, int userCount) {
        String run = UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(status -> {
            User organizer = new User("organizer-" + run + "@test.dev", "hash", "Organizer", UserRole.ORGANIZER);
            em.persist(organizer);
            Venue venue = new Venue("Test Arena " + run, "1 Test Street", "Hanoi");
            em.persist(venue);

            Event event = event(organizer, venue, "Concert " + run);
            em.persist(event);
            Event otherEvent = event(organizer, venue, "Other " + run);
            em.persist(otherEvent);

            TicketZone standing = new TicketZone(event, "Standing", ZoneType.GENERAL_ADMISSION, 300_000, capacity);
            em.persist(standing);
            TicketZone seated = new TicketZone(event, "Seated", ZoneType.SEATED, 500_000, 6);
            em.persist(seated);
            TicketZone otherStanding = new TicketZone(otherEvent, "Standing", ZoneType.GENERAL_ADMISSION, 300_000, 10);
            em.persist(otherStanding);

            List<UUID> seatIds = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                Seat seat = new Seat(seated, "A", i);
                em.persist(seat);
                seatIds.add(seat.getPublicId());
            }

            List<UUID> userIds = new ArrayList<>();
            for (int i = 0; i < userCount; i++) {
                User user = new User("user" + i + "-" + run + "@test.dev", "hash", "User " + i, UserRole.CUSTOMER);
                em.persist(user);
                userIds.add(user.getPublicId());
            }
            em.flush();
            // only a published event sells tickets
            em.createQuery("update Event e set e.status = com.bidtix.event.domain.EventStatus.PUBLISHED where e.id in :ids")
                    .setParameter("ids", List.of(event.getId(), otherEvent.getId()))
                    .executeUpdate();
            return new Fixture(event.getPublicId(), standing.getPublicId(), standing.getId(), standing.getPrice(),
                    seated.getPublicId(), seatIds, otherStanding.getPublicId(), userIds);
        });
    }

    private static Event event(User organizer, Venue venue, String title) {
        Instant now = Instant.now();
        return new Event(organizer.getId(), venue, title, null,
                now.plus(30, ChronoUnit.DAYS), now.plus(31, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.DAYS), now.plus(29, ChronoUnit.DAYS),
                1_000_000);   // tests book freely with one user; TicketLimitTest sets its own limit
    }
}
