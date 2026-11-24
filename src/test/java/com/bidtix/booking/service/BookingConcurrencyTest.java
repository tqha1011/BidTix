package com.bidtix.booking.service;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.dtos.BaseBookingTicketResult;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Event;
import com.bidtix.event.domain.Seat;
import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.domain.Venue;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.payment.service.PayableOrders;
import com.bidtix.support.OrderPayments;
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
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires many parallel bookings at a real PostgreSQL and checks the invariants that must hold no matter
 * how the requests interleave: a seat is held by at most one order, and so sold at most once once the orders are
 * paid (booking only holds; paying is what sells the seats and issues the tickets).
 */
class BookingConcurrencyTest extends PostgresIntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PayableOrders payableOrders;

    /** One event with a single SEATED zone, its seats and a pool of customers. */
    private record Fixture(UUID eventId, UUID zoneId, long zoneDbId, List<UUID> seatIds, List<UUID> userIds) {
    }

    /** Outcome of one parallel booking. */
    private record Attempt(BaseBookingTicketResult result, Throwable error) {
    }

    @Test
    void manyUsersRaceForTheSameSeat_exactlyOneWins() throws Exception {
        Fixture f = fixture(1, 30);
        UUID seat = f.seatIds().get(0);

        List<Attempt> attempts = runInParallel(f.userIds().stream()
                .<Callable<BaseBookingTicketResult>>map(user -> () -> book(f, user, List.of(seat)))
                .toList());

        List<Attempt> wins = successes(attempts);
        assertThat(wins).as("number of successful bookings").hasSize(1);
        assertOnlySeatUnavailableFailures(attempts);

        assertThat(wins.get(0).result().tickets()).as("booking issues no ticket").isEmpty();
        assertThat(seatsWithStatus(f, "HELD")).as("the winner holds the seat").isEqualTo(1);
        assertThat(ticketsInZone(f)).as("no tickets before payment").isZero();

        payAll(wins);

        assertThat(ticketsInZone(f)).as("tickets in the DB").isEqualTo(1);
        assertThat(seatsWithStatus(f, "SOLD")).isEqualTo(1);
        assertThat(ordersOfUsers(f)).as("losers must leave no order behind").isEqualTo(1);
    }

    @Test
    void usersWithOverlappingSeatSets_neverSellASeatTwice_andNeverDeadlock() throws Exception {
        int seatCount = 10;
        Fixture f = fixture(seatCount, 60);
        Random random = new Random(42);   // fixed seed: a failure can be reproduced

        // every user wants 2-3 random seats out of the same 10, listed in a random order
        List<Callable<BaseBookingTicketResult>> jobs = new ArrayList<>();
        for (UUID user : f.userIds()) {
            List<UUID> wanted = new ArrayList<>(f.seatIds());
            Collections.shuffle(wanted, random);
            List<UUID> pick = wanted.subList(0, 2 + random.nextInt(2));
            jobs.add(() -> book(f, user, pick));
        }

        List<Attempt> attempts = runInParallel(jobs);

        // a deadlock or constraint violation would show up here as a non-BusinessException
        assertOnlySeatUnavailableFailures(attempts);

        List<Attempt> wins = successes(attempts);
        int ticketsInResponses = seatsWithStatus(f, "HELD");   // each seat held is one ticket once its order is paid

        assertThat(wins).as("at least one booking must succeed").isNotEmpty();
        assertThat(ticketsInResponses).as("seats held by the winning orders").isLessThanOrEqualTo(seatCount);

        payAll(wins);

        assertThat(ticketsInZone(f)).as("tickets in the DB").isEqualTo(ticketsInResponses);
        assertThat(seatsWithStatus(f, "SOLD")).as("seats marked SOLD").isEqualTo(ticketsInResponses);
        assertThat(seatsWithStatus(f, "HELD")).as("no seat may stay HELD").isZero();
        assertThat(distinctTicketSeats(f)).as("distinct seats among tickets").isEqualTo(ticketsInResponses);
        assertThat(ordersOfUsers(f)).as("only successful bookings leave an order").isEqualTo(wins.size());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Pays every winning order, the way the payment module will: that sells the seats and issues the tickets. */
    private void payAll(List<Attempt> wins) {
        wins.forEach(a -> OrderPayments.pay(payableOrders, jdbc, a.result().orderCode()));
    }

    private BaseBookingTicketResult book(Fixture f, UUID userId, List<UUID> seats) {
        return bookingService.bookTicket(new BookingTicketRequest(f.eventId(), f.zoneId(), seats, null), userId);
    }

    /** Starts every job at the same instant and waits for all of them. */
    private List<Attempt> runInParallel(List<Callable<BaseBookingTicketResult>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch ready = new CountDownLatch(jobs.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Attempt>> futures = jobs.stream()
                    .map(job -> pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        try {
                            return new Attempt(job.call(), null);
                        } catch (Throwable t) {
                            return new Attempt(null, t);
                        }
                    }))
                    .toList();
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all threads started").isTrue();
            go.countDown();

            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(60, TimeUnit.SECONDS));
            }
            return attempts;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Attempt> successes(List<Attempt> attempts) {
        return attempts.stream().filter(a -> a.error() == null).toList();
    }

    /** Losing a race is fine, but it must always be the clean SEAT_UNAVAILABLE business error. */
    private static void assertOnlySeatUnavailableFailures(List<Attempt> attempts) {
        List<Throwable> unexpected = attempts.stream()
                .map(Attempt::error)
                .filter(e -> e != null)
                .filter(e -> !(e instanceof BusinessException be && be.getErrorCode() == ErrorCode.SEAT_UNAVAILABLE))
                .toList();
        assertThat(unexpected).as("failures other than SEAT_UNAVAILABLE").isEmpty();
    }

    private int ticketsInZone(Fixture f) {
        return jdbc.queryForObject("select count(*) from tickets where zone_id = ?", Integer.class, f.zoneDbId());
    }

    private int distinctTicketSeats(Fixture f) {
        return jdbc.queryForObject("select count(distinct seat_id) from tickets where zone_id = ?",
                Integer.class, f.zoneDbId());
    }

    private int seatsWithStatus(Fixture f, String status) {
        return jdbc.queryForObject("select count(*) from seats where zone_id = ? and status = ?",
                Integer.class, f.zoneDbId(), status);
    }

    private int ordersOfUsers(Fixture f) {
        return jdbc.queryForObject("select count(*) from orders o join events e on e.id = o.event_id "
                + "where e.public_id = ?", Integer.class, f.eventId());
    }

    /** Creates its own event, zone, seats and users, so tests do not depend on each other. */
    private Fixture fixture(int seatCount, int userCount) {
        String run = UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(status -> {
            User organizer = new User("organizer-" + run + "@test.dev", "hash", "Organizer", UserRole.ORGANIZER);
            em.persist(organizer);

            Venue venue = new Venue("Test Arena " + run, "1 Test Street", "Hanoi");
            em.persist(venue);

            Instant now = Instant.now();
            Event event = new Event(organizer.getId(), venue, "Concert " + run, null,
                    now.plus(30, ChronoUnit.DAYS), now.plus(31, ChronoUnit.DAYS),
                    now.minus(1, ChronoUnit.DAYS), now.plus(29, ChronoUnit.DAYS), 10);
            em.persist(event);

            TicketZone zone = new TicketZone(event, "Zone A", ZoneType.SEATED, 500_000, seatCount);
            em.persist(zone);

            List<UUID> seatIds = new ArrayList<>();
            for (int i = 1; i <= seatCount; i++) {
                Seat seat = new Seat(zone, "A", i);
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
            return new Fixture(event.getPublicId(), zone.getPublicId(), zone.getId(), seatIds, userIds);
        });
    }
}
