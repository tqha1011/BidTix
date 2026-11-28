package com.bidtix.booking.service;

import io.micrometer.core.instrument.MeterRegistry;
import static com.bidtix.support.Counters.jobFailures;
import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.infrastructure.BookingGateway;
import com.bidtix.booking.infrastructure.OrderCancellationJob;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The job that gives back the stock of orders nobody paid. Booking leaves an order PENDING_PAYMENT with its stock taken,
 * which is how the tests get such orders.
 */
class OrderCancellationJobTest extends PostgresIntegrationTest {

    @Autowired OrderCancellationJob job;
    @Autowired MeterRegistry meters;
    @Autowired BookingService bookingService;
    @Autowired BookingGateway gateway;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PayableOrders payableOrders;

    @Test
    void anExpiredStandingOrder_isCancelled_andItsQuantityComesBack() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 3);
        assertThat(available(f)).isEqualTo(7);
        expire(code);

        job.cancelExpiredOrders();

        assertThat(statusOf(code)).isEqualTo("CANCELLED");
        assertThat(available(f)).isEqualTo(10);
    }

    @Test
    void anExpiredSeatedOrder_isCancelled_andItsSeatsAreFreeAgain() {
        Fixture f = fixture(10, 1);
        UUID a = f.seatedSeatIds().get(0);
        UUID b = f.seatedSeatIds().get(1);
        String code = bookSeats(f, f.userIds().get(0), a, b);
        assertThat(seatStatus(a)).isEqualTo("HELD");
        expire(code);

        job.cancelExpiredOrders();

        assertThat(statusOf(code)).isEqualTo("CANCELLED");
        for (UUID seat : List.of(a, b)) {
            assertThat(seatStatus(seat)).isEqualTo("AVAILABLE");
            assertThat(jdbc.queryForObject("select held_by_order_id from seats where public_id = ?", Long.class, seat))
                    .isNull();
        }
    }

    // ---- an order whose payment may still be confirmed (bidtix.payment.confirmation-grace: 10m) --------

    @Test
    void anExpiredOrderWithAPendingPayment_keepsItsStockDuringTheGracePeriod() {
        Fixture f = fixture(10, 2);
        String paying = bookQuantity(f, f.userIds().get(0), 3);
        String abandoned = bookQuantity(f, f.userIds().get(1), 2);
        payment(paying, "PENDING");
        expire(paying);
        expire(abandoned);

        job.cancelExpiredOrders();   // both are in the same batch

        assertThat(statusOf(paying)).isEqualTo("PENDING_PAYMENT");
        assertThat(statusOf(abandoned)).as("nobody tried to pay it: no grace").isEqualTo("CANCELLED");
        assertThat(available(f)).isEqualTo(7);
    }

    @Test
    void anOrderWithAPendingPayment_isCancelledOnceTheGracePeriodIsOver() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 3);
        payment(code, "PENDING");
        jdbc.update("update orders set expires_at = now() - interval '11 minutes' where order_code = ?", code);

        job.cancelExpiredOrders();

        assertThat(statusOf(code)).isEqualTo("CANCELLED");
        assertThat(available(f)).isEqualTo(10);
    }

    @Test
    void anExpiredOrderWhosePaymentFailed_getsNoGrace() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 3);
        payment(code, "FAILED");
        expire(code);

        job.cancelExpiredOrders();

        assertThat(statusOf(code)).isEqualTo("CANCELLED");
    }

    @Test
    void anOrderStillInItsPaymentWindow_isLeftAlone() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 2);

        job.cancelExpiredOrders();

        assertThat(statusOf(code)).isEqualTo("PENDING_PAYMENT");
        assertThat(available(f)).isEqualTo(8);
    }

    @Test
    void aPaidOrderPastItsDeadline_isLeftAlone() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 2);
        OrderPayments.pay(payableOrders, jdbc, code);
        expire(code);

        job.cancelExpiredOrders();

        assertThat(statusOf(code)).isEqualTo("PAID");
        assertThat(available(f)).isEqualTo(8);
    }

    @Test
    void anExpiredAuctionOrder_isLeftAlone_andItsTicketIsNotGivenBack() {
        Fixture f = fixture(10, 1);
        // the auction took its ticket from the counter when it was created; only the auction may give it back
        String auction = insertExpiredOrder(f, "AUCTION", f.zoneDbId(), 1);
        String booking = insertExpiredOrder(f, "BOOKING", f.zoneDbId(), 1);
        assertThat(available(f)).isEqualTo(8);

        job.cancelExpiredOrders();

        assertThat(statusOf(auction)).isEqualTo("PENDING_PAYMENT");
        assertThat(statusOf(booking)).isEqualTo("CANCELLED");
        assertThat(available(f)).as("only the booking order's ticket came back").isEqualTo(9);
    }

    @Test
    void anOrderThatCannotBeCancelled_doesNotStopTheOthersInItsBatch() {
        Fixture f = fixture(10, 1);
        long seatedZone = jdbc.queryForObject("select id from ticket_zones where public_id = ?", Long.class, f.seatedZoneId());
        String first = insertExpiredOrder(f, "BOOKING", f.zoneDbId(), 1);
        // a standing item in a seated zone: there is no counter to give the ticket back to, so cancelling it fails
        String broken = insertExpiredOrder(f, "BOOKING", seatedZone, 1);
        String last = insertExpiredOrder(f, "BOOKING", f.zoneDbId(), 1);

        double failuresBefore = jobFailures(meters, "order_cancellation");

        job.cancelExpiredOrders();

        assertThat(jobFailures(meters, "order_cancellation")).as("the failed item is counted").isGreaterThan(failuresBefore);

        assertThat(statusOf(first)).isEqualTo("CANCELLED");
        assertThat(statusOf(last)).isEqualTo("CANCELLED");
        assertThat(statusOf(broken)).as("rolled back, retried on the next run").isEqualTo("PENDING_PAYMENT");
        assertThat(available(f)).isEqualTo(10);
    }

    @Test
    void moreExpiredOrdersThanOneBatch_areAllCancelled() {
        int orders = 250;   // the job reads 100 at a time
        Fixture f = fixture(orders, 1);
        for (int i = 0; i < orders; i++) {
            bookQuantity(f, f.userIds().get(0), 1);
        }
        assertThat(available(f)).isZero();
        jdbc.update("update orders set expires_at = now() - interval '1 minute' where event_id = "
                + "(select id from events where public_id = ?)", f.eventId());

        job.cancelExpiredOrders();

        assertThat(jdbc.queryForObject("select count(*) from orders where event_id = "
                + "(select id from events where public_id = ?) and status = 'CANCELLED'", Integer.class, f.eventId()))
                .isEqualTo(orders);
        assertThat(available(f)).isEqualTo(orders);
    }

    @Test
    void stockThatCameBack_clearsTheGateFlags_soNobodyIsRefusedUntilTheyExpire() {
        Fixture f = fixture(2, 1);
        UUID seat = f.seatedSeatIds().get(0);
        String standing = bookQuantity(f, f.userIds().get(0), 2);
        String seated = bookSeats(f, f.userIds().get(0), seat);
        gateway.setQuantityRejected(f.zoneId(), 1);
        gateway.setSeatFlagged(f.seatedZoneId(), List.of(seat));
        expire(standing);
        expire(seated);

        job.cancelExpiredOrders();

        assertThat(gateway.mayTakeQuantity(f.zoneId(), 1)).isTrue();
        assertThat(gateway.flaggedSeats(f.seatedZoneId(), List.of(seat))).isEmpty();
    }

    @Test
    void severalJobsAtOnce_giveEachOrderBackExactlyOnce() throws Exception {
        int orders = 40;
        Fixture f = fixture(orders, 1);
        for (int i = 0; i < orders; i++) {
            bookQuantity(f, f.userIds().get(0), 1);
        }
        jdbc.update("update orders set expires_at = now() - interval '1 minute' where event_id = "
                + "(select id from events where public_id = ?)", f.eventId());

        int jobs = 4;
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < jobs; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                job.cancelExpiredOrders();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> run : runs) {
            run.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(available(f)).as("never above capacity: no order was refunded twice").isEqualTo(orders);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private Fixture fixture(int capacity, int users) {
        return GeneralAdmissionFixtures.create(em, tx, capacity, users);
    }

    private String bookQuantity(Fixture f, UUID user, int quantity) {
        return bookingService.bookTicket(
                new BookingTicketRequest(f.eventId(), f.zoneId(), null, quantity), user).orderCode();
    }

    private String bookSeats(Fixture f, UUID user, UUID... seats) {
        return bookingService.bookTicket(
                new BookingTicketRequest(f.eventId(), f.seatedZoneId(), List.of(seats), null), user).orderCode();
    }

    /** An unpaid order past its deadline with one standing item, inserted by SQL; the zone counter is lowered like a real hold would. */
    private String insertExpiredOrder(Fixture f, String source, long zoneDbId, int quantity) {
        long userId = jdbc.queryForObject("select id from users where public_id = ?", Long.class, f.userIds().get(0));
        long eventId = jdbc.queryForObject("select id from events where public_id = ?", Long.class, f.eventId());
        String code = "ORDER_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        long orderId = jdbc.queryForObject("insert into orders (user_id, event_id, source, total_amount, expires_at, order_code) "
                + "values (?, ?, ?, 0, now() - interval '1 minute', ?) returning id", Long.class, userId, eventId, source, code);
        jdbc.update("insert into order_items (order_id, zone_id, quantity, unit_price) values (?, ?, ?, 300000)",
                orderId, zoneDbId, quantity);
        if (zoneDbId == f.zoneDbId()) {
            jdbc.update("update ticket_zones set available_quantity = available_quantity - ? where id = ?", quantity, zoneDbId);
        }
        return code;
    }

    private void expire(String orderCode) {
        jdbc.update("update orders set expires_at = now() - interval '1 minute' where order_code = ?", orderCode);
    }

    /** A VNPAY payment attempt of the order in this status. */
    private void payment(String orderCode, String status) {
        jdbc.update("insert into payments (order_id, provider, txn_ref, amount, status) "
                        + "select id, 'VNPAY', ?, total_amount, ? from orders where order_code = ?",
                UUID.randomUUID().toString().replace("-", ""), status, orderCode);
    }

    private String statusOf(String orderCode) {
        return jdbc.queryForObject("select status from orders where order_code = ?", String.class, orderCode);
    }

    private String seatStatus(UUID seatPublicId) {
        return jdbc.queryForObject("select status from seats where public_id = ?", String.class, seatPublicId);
    }

    private int available(Fixture f) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where id = ?", Integer.class, f.zoneDbId());
    }
}
