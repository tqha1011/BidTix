package com.bidtix.benchmark;

import com.bidtix.event.domain.Event;
import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.domain.Venue;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final JdbcTemplate jdbc;

    public BenchmarkFixture(EntityManager em, TransactionTemplate tx, JdbcTemplate jdbc) {
        this.em = em;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    /**
     * Seats and orders are created with one {@code INSERT ... SELECT generate_series} each: persisting hundreds of
     * thousands of entities one by one would take longer than the run being measured. The orders all belong to one
     * simulated user (nothing in the schema limits orders per user).
     */
    public Seated seated(int seats, int customers) {
        return tx.execute(status -> {
            Event event = newEvent();
            TicketZone zone = new TicketZone(event, "Seated", ZoneType.SEATED, 500_000, seats);
            em.persist(zone);
            User user = new User("bench-" + suffix() + "@test.dev", "hash", "Bench", UserRole.CUSTOMER);
            em.persist(user);
            em.flush();

            jdbc.update("insert into seats (zone_id, row_label, seat_number) "
                    + "select ?, 'A', g from generate_series(1, ?) g", zone.getId(), seats);
            List<Long> seatIds = jdbc.queryForList(
                    "select id from seats where zone_id = ? order by id", Long.class, zone.getId());

            Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
            // order_code: "ORDER_" + 14 random hex digits = 20 chars, the width of the column
            jdbc.update("insert into orders (user_id, event_id, source, total_amount, expires_at, order_code) "
                            + "select ?, ?, 'BOOKING', 0, ?, 'ORDER_' || upper(substr(md5(gen_random_uuid()::text), 1, 14)) "
                            + "from generate_series(1, ?)",
                    user.getId(), event.getId(), Timestamp.from(expiresAt), customers);
            List<Long> orderIds = jdbc.queryForList(
                    "select id from orders where event_id = ? order by id", Long.class, event.getId());
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
