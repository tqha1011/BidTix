package com.bidtix.booking.service;

import com.bidtix.booking.dtos.BaseBookingTicketResult;
import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.payment.service.PayableOrders;
import com.bidtix.support.OrderPayments;
import com.bidtix.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires many parallel standing-ticket bookings at a real PostgreSQL. Whatever the interleaving, the
 * counter must never go below zero, never lose an update, and always equal capacity minus what was sold.
 */
class GeneralAdmissionConcurrencyTest extends PostgresIntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PayableOrders payableOrders;

    /** Outcome of one parallel booking. */
    private record Attempt(int quantity, BaseBookingTicketResult result, Throwable error) {
    }

    @Test
    void manyUsersRaceForTheLastTicket_exactlyOneWins() throws Exception {
        Fixture f = GeneralAdmissionFixtures.create(em, tx, 1, 30);

        List<Attempt> attempts = runInParallel(f, f.userIds().stream().map(u -> 1).toList());

        assertOnlySoldOutFailures(attempts);
        assertThat(winners(attempts)).as("successful bookings").hasSize(1);
        assertThat(available(f)).isZero();
        assertThat(ticketsInZone(f)).as("no ticket before payment").isZero();
        payAll(winners(attempts));
        assertThat(ticketsInZone(f)).isEqualTo(1);
        assertThat(orders(f)).as("losers must leave no order behind").isEqualTo(1);
    }

    @Test
    void usersWantingSeveralTicketsEach_neverOversell_andNeverLoseAnUpdate() throws Exception {
        int capacity = 20;
        Fixture f = GeneralAdmissionFixtures.create(em, tx, capacity, 60);
        Random random = new Random(42);   // fixed seed: a failure can be reproduced
        List<Integer> wanted = f.userIds().stream().map(u -> 1 + random.nextInt(5)).toList();

        List<Attempt> attempts = runInParallel(f, wanted);

        assertOnlySoldOutFailures(attempts);
        List<Attempt> wins = winners(attempts);
        int soldInResponses = wins.stream().mapToInt(Attempt::quantity).sum();

        assertThat(wins).as("at least one booking must succeed").isNotEmpty();
        assertThat(soldInResponses).as("tickets promised to clients").isLessThanOrEqualTo(capacity);
        assertThat(available(f)).as("counter == capacity - sold (no lost update)").isEqualTo(capacity - soldInResponses);
        payAll(wins);
        assertThat(ticketsInZone(f)).as("tickets in the DB").isEqualTo(soldInResponses);
        assertThat(orders(f)).as("only successful bookings leave an order").isEqualTo(wins.size());
        // the stock is not wasted either: what is left is smaller than the smallest request that failed
        int smallestLoser = attempts.stream().filter(a -> a.error() != null).mapToInt(Attempt::quantity).min().orElse(Integer.MAX_VALUE);
        assertThat(available(f)).as("left over").isLessThan(smallestLoser);
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Pays every winning order, the way the payment module will: that issues the tickets. */
    private void payAll(List<Attempt> wins) {
        wins.forEach(a -> OrderPayments.pay(payableOrders, jdbc, a.result().orderCode()));
    }

    private void assertOnlySoldOutFailures(List<Attempt> attempts) {
        List<Throwable> unexpected = attempts.stream()
                .map(Attempt::error)
                .filter(e -> e != null)
                .filter(e -> !(e instanceof BusinessException be && be.getErrorCode() == ErrorCode.ZONE_SOLD_OUT))
                .toList();
        assertThat(unexpected).as("failures other than ZONE_SOLD_OUT").isEmpty();
    }

    private static List<Attempt> winners(List<Attempt> attempts) {
        return attempts.stream().filter(a -> a.result() != null).toList();
    }

    /** One job per user, all released at the same instant. */
    private List<Attempt> runInParallel(Fixture f, List<Integer> quantities) throws Exception {
        int jobs = quantities.size();
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        CountDownLatch ready = new CountDownLatch(jobs);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (int i = 0; i < jobs; i++) {
                UUID user = f.userIds().get(i);
                int quantity = quantities.get(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Attempt(quantity, bookingService.bookTicket(
                                new BookingTicketRequest(f.eventId(), f.zoneId(), null, quantity), user, UUID.randomUUID().toString()), null);
                    } catch (Throwable t) {
                        return new Attempt(quantity, null, t);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("all threads ready").isTrue();
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

    private int available(Fixture f) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where id = ?", Integer.class, f.zoneDbId());
    }

    private int ticketsInZone(Fixture f) {
        return jdbc.queryForObject("select count(*) from tickets where zone_id = ?", Integer.class, f.zoneDbId());
    }

    private int orders(Fixture f) {
        return jdbc.queryForObject("select count(*) from orders o join events e on e.id = o.event_id where e.public_id = ?",
                Integer.class, f.eventId());
    }
}
