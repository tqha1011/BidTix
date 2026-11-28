package com.bidtix.booking.service;

import com.bidtix.booking.infrastructure.BookingBacklogGauges;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database is shared and the scheduled jobs run, so every count is compared with what it was before, and the rows
 * added here are ones no job can deal with.
 */
class BookingBacklogGaugesTest extends PostgresIntegrationTest {

    @Autowired BookingBacklogGauges gauges;
    @Autowired MeterRegistry meters;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> orders = new ArrayList<>();
    private final List<UUID> events = new ArrayList<>();

    /** Otherwise the jobs keep failing on these rows for as long as the tests run. */
    @AfterEach
    void cleanUp() {
        orders.forEach(id -> jdbc.update("update orders set status = 'CANCELLED' where id = ?", id));
        events.forEach(event -> jdbc.update("update auctions set status = 'CANCELLED' where event_id = "
                + "(select id from events where public_id = ?)", event));
    }

    @Test
    void bookingOrdersStillUnpaid15MinutesAfterTheirDeadlineAreCounted() {
        Fixture f = fixture();
        double before = read("bidtix.orders.expired.uncancelled");

        unpaidOrder(f, "BOOKING", "16 minutes");
        unpaidOrder(f, "BOOKING", "14 minutes");   // may still be in its grace period
        unpaidOrder(f, "AUCTION", "16 minutes");   // the auction module's

        assertThat(read("bidtix.orders.expired.uncancelled")).isEqualTo(before + 1);
    }

    @Test
    void eventsCancelled10MinutesAgoWhoseOrdersAreNotCleanedUpAreCounted() {
        double before = read();

        cancelledAndStuck("11 minutes");
        cancelledAndStuck("9 minutes");

        assertThat(read()).isEqualTo(before + 1);
    }

    private Fixture fixture() {
        return GeneralAdmissionFixtures.create(em, tx, 10, 1);
    }

    /** With a standing item in a seated zone, so that the cancellation job fails on it and leaves it. */
    private void unpaidOrder(Fixture f, String source, String overdueBy) {
        long seatedZone = jdbc.queryForObject("select id from ticket_zones where public_id = ?", Long.class,
                f.seatedZoneId());
        long id = jdbc.queryForObject("insert into orders (user_id, event_id, source, total_amount, expires_at, order_code) "
                        + "select u.id, e.id, ?, 0, now() - cast(? as interval), ? from users u, events e "
                        + "where u.public_id = ? and e.public_id = ? returning id", Long.class, source, overdueBy,
                "ORDER_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(),
                f.userIds().getFirst(), f.eventId());
        jdbc.update("insert into order_items (order_id, zone_id, quantity, unit_price) values (?, ?, 1, 300000)", id,
                seatedZone);
        orders.add(id);
    }

    /** An auction awaiting payment without an offer: the auction job leaves it, so neither module marks the event. */
    private void cancelledAndStuck(String ago) {
        Fixture f = fixture();
        jdbc.update("insert into auctions (event_id, zone_id, start_price, min_increment, starts_at, ends_at, status) "
                + "select id, ?, 100000, 10000, now() - interval '2 hours', now() - interval '1 hour', "
                + "'AWAITING_PAYMENT' from events where public_id = ?", f.zoneDbId(), f.eventId());
        jdbc.update("update events set status = 'CANCELLED', cancelled_at = now() - cast(? as interval) "
                + "where public_id = ?", ago, f.eventId());
        events.add(f.eventId());
    }

    private double read() {
        gauges.refresh();
        return meters.get("bidtix.events.cancelled.uncleaned").tag("module", "booking").gauge().value();
    }

    private double read(String gauge) {
        gauges.refresh();
        return meters.get(gauge).gauge().value();
    }
}
