package com.bidtix.booking.service;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.infrastructure.OrderCancellationJob;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.payment.service.PayableOrders;
import com.bidtix.support.OrderPayments;
import com.bidtix.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code max_tickets_per_user}: the booked tickets a user holds (unpaid) or owns (paid) for one event never exceed it,
 * however many requests arrive at once. Auction orders do not count.
 */
class TicketLimitTest extends PostgresIntegrationTest {

    private static final int LIMIT = 4;

    @Autowired BookingService bookingService;
    @Autowired OrderCancellationJob cancellationJob;
    @Autowired PayableOrders payableOrders;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    void anOrderBiggerThanTheLimit_isRefused_andTakesNothing() {
        Fixture f = fixture(1);
        UUID user = f.userIds().getFirst();

        assertOverTheLimit(() -> standing(f, user, LIMIT + 1));

        assertThat(available(f)).isEqualTo(20);
        assertThat(orderCount(f)).isZero();
    }

    @Test
    void ordersAddUp_untilTheLimitIsReached() {
        Fixture f = fixture(1);
        UUID user = f.userIds().getFirst();
        standing(f, user, 3);

        assertOverTheLimit(() -> standing(f, user, 2));
        standing(f, user, 1);
        assertOverTheLimit(() -> standing(f, user, 1));

        assertThat(available(f)).isEqualTo(16);
    }

    @Test
    void seatsAndStandingTicketsOfTheEventCountTogether() {
        Fixture f = fixture(1);
        UUID user = f.userIds().getFirst();
        seats(f, user, f.seatedSeatIds().get(0), f.seatedSeatIds().get(1));

        assertOverTheLimit(() -> standing(f, user, 3));
        standing(f, user, 2);
        assertOverTheLimit(() -> seats(f, user, f.seatedSeatIds().get(2)));

        assertThat(jdbc.queryForObject("select status from seats where public_id = ?", String.class,
                f.seatedSeatIds().get(2))).isEqualTo("AVAILABLE");
    }

    @Test
    void paidTicketsStillCount() {
        Fixture f = fixture(1);
        UUID user = f.userIds().getFirst();
        OrderPayments.pay(payableOrders, jdbc, standing(f, user, LIMIT));

        assertOverTheLimit(() -> standing(f, user, 1));
    }

    @Test
    void aCancelledOrderGivesTheAllowanceBack() {
        Fixture f = fixture(1);
        UUID user = f.userIds().getFirst();
        String code = standing(f, user, LIMIT);
        jdbc.update("update orders set expires_at = now() - interval '1 minute' where order_code = ?", code);
        cancellationJob.cancelExpiredOrders();

        standing(f, user, LIMIT);

        assertThat(available(f)).isEqualTo(16);
    }

    @Test
    void ticketsWonAtAuctionDoNotCount() {
        Fixture f = fixture(1);
        UUID user = f.userIds().getFirst();
        long orderId = jdbc.queryForObject("insert into orders (user_id, event_id, source, total_amount, expires_at, "
                        + "order_code) select u.id, e.id, 'AUCTION', 0, now() + interval '10 minutes', ? from users u, "
                        + "events e where u.public_id = ? and e.public_id = ? returning id", Long.class,
                "ORDER_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(),
                user, f.eventId());
        jdbc.update("insert into order_items (order_id, zone_id, quantity, unit_price) values (?, ?, 1, 300000)",
                orderId, f.zoneDbId());

        standing(f, user, LIMIT);
    }

    @Test
    void everyUserAndEveryEventHasItsOwnAllowance() {
        Fixture f = fixture(2);
        UUID first = f.userIds().get(0);
        standing(f, first, LIMIT);

        standing(f, f.userIds().get(1), LIMIT);
        UUID otherEvent = jdbc.queryForObject("select e.public_id from events e join ticket_zones z on z.event_id = e.id "
                + "where z.public_id = ?", UUID.class, f.otherEventZoneId());
        bookingService.bookTicket(new BookingTicketRequest(otherEvent, f.otherEventZoneId(), null, LIMIT), first);
    }

    @Test
    void oneUserSendingManyRequestsAtOnce_neverGetsMoreThanTheLimit() throws Exception {
        Fixture f = fixture(1);
        UUID user = f.userIds().getFirst();
        int requests = 12;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ErrorCode>> calls = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            // half take a standing ticket, half a seat each: the limit is per event, not per zone
            UUID seat = i < 6 ? f.seatedSeatIds().get(i) : null;
            calls.add(pool.submit(() -> {
                start.await();
                try {
                    if (seat == null) {
                        standing(f, user, 1);
                    } else {
                        seats(f, user, seat);
                    }
                    return null;
                } catch (BusinessException e) {
                    return e.getErrorCode();
                }
            }));
        }
        start.countDown();
        List<ErrorCode> outcomes = new ArrayList<>();
        for (Future<ErrorCode> call : calls) {
            outcomes.add(call.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(outcomes).filteredOn(code -> code == null).as("successful bookings").hasSize(LIMIT);
        assertThat(outcomes).filteredOn(code -> code != null).containsOnly(ErrorCode.TICKET_LIMIT_EXCEEDED);
        assertThat(jdbc.queryForObject("select coalesce(sum(i.quantity), 0) from orders o join order_items i "
                + "on i.order_id = o.id join events e on e.id = o.event_id where e.public_id = ?", Integer.class,
                f.eventId())).isEqualTo(LIMIT);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private Fixture fixture(int users) {
        Fixture f = GeneralAdmissionFixtures.create(em, tx, 20, users);
        jdbc.update("update events set max_tickets_per_user = ? where public_id = ? or id = "
                        + "(select event_id from ticket_zones where public_id = ?)", LIMIT, f.eventId(),
                f.otherEventZoneId());
        return f;
    }

    private String standing(Fixture f, UUID user, int quantity) {
        return bookingService.bookTicket(new BookingTicketRequest(f.eventId(), f.zoneId(), null, quantity), user)
                .orderCode();
    }

    private String seats(Fixture f, UUID user, UUID... seatIds) {
        return bookingService.bookTicket(
                new BookingTicketRequest(f.eventId(), f.seatedZoneId(), List.of(seatIds), null), user).orderCode();
    }

    private static void assertOverTheLimit(ThrowingCallable booking) {
        assertThatThrownBy(booking).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TICKET_LIMIT_EXCEEDED));
    }

    private int available(Fixture f) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where id = ?", Integer.class, f.zoneDbId());
    }

    private int orderCount(Fixture f) {
        return jdbc.queryForObject("select count(*) from orders o join events e on e.id = o.event_id "
                + "where e.public_id = ?", Integer.class, f.eventId());
    }
}
