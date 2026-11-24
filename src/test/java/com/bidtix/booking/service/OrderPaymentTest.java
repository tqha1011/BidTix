package com.bidtix.booking.service;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.payment.service.PayableOrders;
import com.bidtix.payment.service.PayableOrders.Applied;
import com.bidtix.payment.service.PayableOrders.PayableOrder;
import com.bidtix.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
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
 * {@code OrderPayment}, the booking side of {@code PayableOrders}. Booking an order leaves it PENDING_PAYMENT with its stock
 * taken, which is the order a provider's confirmation arrives for.
 */
class OrderPaymentTest extends PostgresIntegrationTest {

    @Autowired PayableOrders orders;
    @Autowired BookingService bookingService;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    // ---- looking an order up -------------------------------------------------------------------

    @Test
    void findOwnOrder_returnsTheOrderAsThePaymentModuleSeesIt() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 2);

        PayableOrder order = orders.findOwnOrder(code, f.userIds().get(0)).orElseThrow();

        assertThat(order.id()).isEqualTo(idOf(code));
        assertThat(order.code()).isEqualTo(code);
        assertThat(order.amount()).isEqualTo(2 * f.price());
        assertThat(order.deadline()).isAfter(Instant.now());
        assertThat(order.awaitingPayment()).isTrue();
    }

    @Test
    void findOwnOrder_isEmptyForAnotherCustomersOrderAndForAnUnknownCode() {
        Fixture f = fixture(10, 2);
        String code = bookQuantity(f, f.userIds().get(0), 1);

        assertThat(orders.findOwnOrder(code, f.userIds().get(1))).isEmpty();
        assertThat(orders.findOwnOrder("ORDER_UNKNOWN", f.userIds().get(0))).isEmpty();
    }

    @Test
    void anOrderThatIsNotPendingAnyMore_isNotAwaitingPayment() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 1);
        jdbc.update("update orders set status = 'CANCELLED' where order_code = ?", code);

        assertThat(orders.findOrder(idOf(code)).orElseThrow().awaitingPayment()).isFalse();
        assertThat(orders.findOrder(-1L)).isEmpty();
    }

    // ---- applying a confirmed payment ----------------------------------------------------------

    @Test
    void applyPayment_marksAStandingOrderPaid_andIssuesOneTicketPerUnit() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 3);

        assertThat(orders.applyPayment(idOf(code), Instant.now())).isEqualTo(Applied.APPLIED);

        assertThat(statusOf(code)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select paid_at is not null from orders where order_code = ?", Boolean.class, code)).isTrue();
        assertThat(ticketsOf(code)).isEqualTo(3);
    }

    @Test
    void applyPayment_marksSeatsSold_andIssuesOneTicketPerSeat() {
        Fixture f = fixture(10, 1);
        UUID a = f.seatedSeatIds().get(0);
        UUID b = f.seatedSeatIds().get(1);
        String code = bookSeats(f, f.userIds().get(0), a, b);

        assertThat(orders.applyPayment(idOf(code), Instant.now())).isEqualTo(Applied.APPLIED);

        assertThat(statusOf(code)).isEqualTo("PAID");
        assertThat(seatStatus(a)).isEqualTo("SOLD");
        assertThat(seatStatus(b)).isEqualTo("SOLD");
        assertThat(ticketsOf(code)).isEqualTo(2);
    }

    @Test
    void applyPayment_aSecondTimeIsAlreadyPaid_andIssuesNothingMore() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 2);
        assertThat(orders.applyPayment(idOf(code), Instant.now())).isEqualTo(Applied.APPLIED);

        assertThat(orders.applyPayment(idOf(code), Instant.now())).isEqualTo(Applied.ALREADY_PAID);

        assertThat(ticketsOf(code)).isEqualTo(2);
    }

    @Test
    void applyPayment_onACancelledOrderIsNotPayable_andChangesNothing() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 2);
        jdbc.update("update orders set status = 'CANCELLED' where order_code = ?", code);

        assertThat(orders.applyPayment(idOf(code), Instant.now())).isEqualTo(Applied.NOT_PAYABLE);

        assertThat(statusOf(code)).isEqualTo("CANCELLED");
        assertThat(ticketsOf(code)).isZero();
    }

    @Test
    void applyPayment_whenASeatHoldWasLost_failsAndRollsBackEverything() {
        Fixture f = fixture(10, 1);
        UUID kept = f.seatedSeatIds().get(0);
        UUID lost = f.seatedSeatIds().get(1);
        String code = bookSeats(f, f.userIds().get(0), kept, lost);
        jdbc.update("update seats set status = 'AVAILABLE', held_by_order_id = null, hold_expires_at = null where public_id = ?", lost);

        assertThatThrownBy(() -> orders.applyPayment(idOf(code), Instant.now()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_PAYABLE));

        assertThat(statusOf(code)).isEqualTo("PENDING_PAYMENT");   // marked paid, then rolled back
        assertThat(seatStatus(kept)).isEqualTo("HELD");             // not sold either
        assertThat(ticketsOf(code)).isZero();
    }

    @Test
    void applyPayment_runInParallel_paysTheOrderOnce() throws Exception {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 2);
        long orderId = idOf(code);
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Applied>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return orders.applyPayment(orderId, Instant.now());
            }));
        }
        ready.await();
        go.countDown();
        List<Applied> applied = new ArrayList<>();
        for (Future<Applied> result : results) {
            applied.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(applied).containsOnlyOnce(Applied.APPLIED);
        assertThat(applied.stream().filter(a -> a == Applied.ALREADY_PAID)).hasSize(callers - 1);
        assertThat(ticketsOf(code)).isEqualTo(2);   // one set of tickets, not one per caller
        assertThat(statusOf(code)).isEqualTo("PAID");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Fixture fixture(int capacity, int users) {
        return GeneralAdmissionFixtures.create(em, tx, capacity, users);
    }

    private String bookQuantity(Fixture f, UUID user, int quantity) {
        return bookingService.bookTicket(new BookingTicketRequest(f.eventId(), f.zoneId(), null, quantity), user).orderCode();
    }

    private String bookSeats(Fixture f, UUID user, UUID... seats) {
        return bookingService.bookTicket(new BookingTicketRequest(f.eventId(), f.seatedZoneId(), List.of(seats), null), user).orderCode();
    }

    private long idOf(String orderCode) {
        return jdbc.queryForObject("select id from orders where order_code = ?", Long.class, orderCode);
    }

    private String statusOf(String orderCode) {
        return jdbc.queryForObject("select status from orders where order_code = ?", String.class, orderCode);
    }

    private int ticketsOf(String orderCode) {
        return jdbc.queryForObject("select count(*) from tickets t join orders o on o.id = t.order_id where o.order_code = ?",
                Integer.class, orderCode);
    }

    private String seatStatus(UUID seatPublicId) {
        return jdbc.queryForObject("select status from seats where public_id = ?", String.class, seatPublicId);
    }
}
