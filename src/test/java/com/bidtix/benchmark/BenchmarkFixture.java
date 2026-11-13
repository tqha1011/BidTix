package com.bidtix.benchmark;

import com.bidtix.booking.domain.Order;
import com.bidtix.booking.domain.OrderSource;
import com.bidtix.common.utils.GenerateOrderCode;
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

/** Creates the data for one run. Every call builds its own event, zone and users: runs never share rows. */
public final class BenchmarkFixture {

    /** A SEATED zone and one {@code PENDING_PAYMENT} order per simulated customer. */
    public record Seated(long zoneId, List<Long> seatIds, List<Long> orderIds, Instant expiresAt) {
    }

    public record Standing(long zoneId, int capacity) {
    }

    private final EntityManager em;
    private final TransactionTemplate tx;

    public BenchmarkFixture(EntityManager em, TransactionTemplate tx) {
        this.em = em;
        this.tx = tx;
    }

    public Seated seated(int seats, int customers) {
        return tx.execute(status -> {
            Event event = newEvent();
            TicketZone zone = new TicketZone(event, "Seated", ZoneType.SEATED, 500_000, seats);
            em.persist(zone);

            List<Long> seatIds = new ArrayList<>();
            for (int i = 1; i <= seats; i++) {
                Seat seat = new Seat(zone, "A", i);
                em.persist(seat);
                seatIds.add(seat.getId());
            }

            String run = suffix();
            Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
            List<Long> orderIds = new ArrayList<>();
            for (int i = 0; i < customers; i++) {
                User user = new User("bench" + i + "-" + run + "@test.dev", "hash", "Bench " + i, UserRole.CUSTOMER);
                em.persist(user);
                Order order = new Order(user.getId(), event.getId(), GenerateOrderCode.generate(),
                        OrderSource.BOOKING, expiresAt);
                em.persist(order);
                orderIds.add(order.getId());
            }
            em.flush();
            return new Seated(zone.getId(), seatIds, orderIds, expiresAt);
        });
    }

    public Standing standing(int capacity) {
        return tx.execute(status -> {
            Event event = newEvent();
            TicketZone zone = new TicketZone(event, "Standing", ZoneType.GENERAL_ADMISSION, 300_000, capacity);
            em.persist(zone);
            em.flush();
            return new Standing(zone.getId(), capacity);
        });
    }

    private Event newEvent() {
        String run = suffix();
        User organizer = new User("bench-org-" + run + "@test.dev", "hash", "Organizer", UserRole.ORGANIZER);
        em.persist(organizer);
        Venue venue = new Venue("Bench Arena " + run, "1 Test Street", "Hanoi");
        em.persist(venue);
        Instant now = Instant.now();
        Event event = new Event(organizer.getId(), venue, "Bench " + run, null,
                now.plus(30, ChronoUnit.DAYS), now.plus(31, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.DAYS), now.plus(29, ChronoUnit.DAYS), 10);
        em.persist(event);
        return event;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
