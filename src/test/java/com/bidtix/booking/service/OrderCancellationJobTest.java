package com.bidtix.booking.service;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.infrastructure.BookingGateway;
import com.bidtix.booking.infrastructure.OrderCancellationJob;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.payment.service.PaymentGateway;
import com.bidtix.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The job that gives back the stock of orders nobody paid. A payment that fails leaves the order
 * PENDING_PAYMENT with its stock taken, which is how the tests get such orders.
 */
class OrderCancellationJobTest extends PostgresIntegrationTest {

    @Autowired OrderCancellationJob job;
    @Autowired BookingService bookingService;
    @Autowired BookingGateway gateway;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGateway paymentGateway;

    @BeforeEach
    void paymentFails() {
        when(paymentGateway.charge(anyString(), anyLong())).thenReturn(false);
    }

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
        when(paymentGateway.charge(anyString(), anyLong())).thenReturn(true);
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 2);
        expire(code);

        job.cancelExpiredOrders();

        assertThat(statusOf(code)).isEqualTo("PAID");
        assertThat(available(f)).isEqualTo(8);
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

    private void expire(String orderCode) {
        jdbc.update("update orders set expires_at = now() - interval '1 minute' where order_code = ?", orderCode);
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
