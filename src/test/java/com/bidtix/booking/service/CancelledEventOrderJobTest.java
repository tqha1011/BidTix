package com.bidtix.booking.service;

import io.micrometer.core.instrument.MeterRegistry;
import static com.bidtix.support.Counters.jobFailures;
import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.infrastructure.BookingGateway;
import com.bidtix.booking.infrastructure.CancelledEventOrderJob;
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
 * The job that cleans up the orders of a cancelled event: unpaid booking orders are cancelled and give their stock back,
 * paid orders are cancelled with their tickets and their money is marked for refund. The scheduled run of the same job
 * also works on the shared database, so every assertion is about a state that stays true whoever got there.
 */
class CancelledEventOrderJobTest extends PostgresIntegrationTest {

    @Autowired CancelledEventOrderJob job;
    @Autowired MeterRegistry meters;
    @Autowired BookingService bookingService;
    @Autowired BookingGateway gateway;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PayableOrders payableOrders;

    @Test
    void anUnpaidStandingOrder_isCancelledBeforeItsDeadline_andItsQuantityComesBack() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 3);
        cancelEvent(f);

        job.cancelOrdersOfCancelledEvents();

        assertThat(statusOf(code)).isEqualTo("CANCELLED");
        assertThat(available(f)).isEqualTo(10);
    }

    @Test
    void anUnpaidSeatedOrder_isCancelled_andItsSeatsAreFreeAgain() {
        Fixture f = fixture(10, 1);
        UUID a = f.seatedSeatIds().get(0);
        UUID b = f.seatedSeatIds().get(1);
        String code = bookSeats(f, f.userIds().get(0), a, b);
        cancelEvent(f);

        job.cancelOrdersOfCancelledEvents();

        assertThat(statusOf(code)).isEqualTo("CANCELLED");
        for (UUID seat : List.of(a, b)) {
            assertThat(seatStatus(seat)).isEqualTo("AVAILABLE");
        }
    }

    @Test
    void anOrderWithAPendingPayment_getsNoGracePeriod() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 3);
        jdbc.update("insert into payments (order_id, provider, txn_ref, amount, status) "
                        + "select id, 'VNPAY', ?, total_amount, 'PENDING' from orders where order_code = ?",
                UUID.randomUUID().toString().replace("-", ""), code);
        cancelEvent(f);

        job.cancelOrdersOfCancelledEvents();

        assertThat(statusOf(code)).isEqualTo("CANCELLED");
    }

    @Test
    void anOrderOfAnEventThatIsNotCancelled_isLeftAlone() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 2);

        job.cancelOrdersOfCancelledEvents();

        assertThat(statusOf(code)).isEqualTo("PENDING_PAYMENT");
        assertThat(available(f)).isEqualTo(8);
    }

    @Test
    void anEventWhoseOrdersAreMarkedCleaned_isNotLookedAtAgain() {
        Fixture f = fixture(10, 1);
        String code = bookQuantity(f, f.userIds().get(0), 2);
        jdbc.update("update events set status = 'CANCELLED', cancelled_at = now(), orders_cleaned_at = now() "
                + "where public_id = ?", f.eventId());

        job.cancelOrdersOfCancelledEvents();

        assertThat(statusOf(code)).isEqualTo("PENDING_PAYMENT");
    }

    // ---- paid orders ------------------------------------------------------------------------

    @Test
    void aPaidStandingOrder_isCancelledWithItsTickets_andItsMoneyIsMarkedForRefund() {
        Fixture f = fixture(10, 1);
        String code = paidQuantity(f, 2);
        cancelEvent(f);

        job.cancelOrdersOfCancelledEvents();

        assertThat(statusOf(code)).isEqualTo("CANCELLED");   // although its payment deadline is still ahead
        assertThat(ticketStatuses(code)).containsExactly("CANCELLED", "CANCELLED");
        assertThat(paymentStatusOf(code)).isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("select failure_reason from payments p join orders o on o.id = p.order_id "
                + "where o.order_code = ?", String.class, code)).isEqualTo("The event was cancelled");
        assertThat(available(f)).as("nobody can buy for a cancelled event: nothing goes back on sale").isEqualTo(8);
    }

    @Test
    void aPaidSeatedOrder_isRefunded_andItsSeatStaysSold() {
        Fixture f = fixture(10, 1);
        UUID seat = f.seatedSeatIds().get(0);
        String code = bookSeats(f, f.userIds().get(0), seat);
        pay(code);
        cancelEvent(f);

        job.cancelOrdersOfCancelledEvents();

        assertThat(statusOf(code)).isEqualTo("CANCELLED");
        assertThat(ticketStatuses(code)).containsExactly("CANCELLED");
        assertThat(paymentStatusOf(code)).isEqualTo("REFUND_PENDING");
        assertThat(seatStatus(seat)).isEqualTo("SOLD");
    }

    @Test
    void aPaidAuctionOrder_isRefundedToo() {
        Fixture f = fixture(10, 1);
        String code = insertUnpaidOrder(f, "AUCTION", f.zoneDbId());
        jdbc.update("update orders set status = 'PAID', paid_at = now(), total_amount = 300000 where order_code = ?", code);
        jdbc.update("insert into tickets (order_id, order_item_id, event_id, zone_id, owner_id) "
                + "select o.id, i.id, o.event_id, i.zone_id, o.user_id from orders o join order_items i on i.order_id = o.id "
                + "where o.order_code = ?", code);
        succeededPayment(code);
        cancelEvent(f);

        job.cancelOrdersOfCancelledEvents();

        assertThat(statusOf(code)).isEqualTo("CANCELLED");
        assertThat(ticketStatuses(code)).containsExactly("CANCELLED");
        assertThat(paymentStatusOf(code)).isEqualTo("REFUND_PENDING");
    }

    @Test
    void aPaidOrderOfAnEventThatIsNotCancelled_keepsItsTickets() {
        Fixture f = fixture(10, 1);
        String code = paidQuantity(f, 1);

        job.cancelOrdersOfCancelledEvents();

        assertThat(statusOf(code)).isEqualTo("PAID");
        assertThat(ticketStatuses(code)).containsExactly("VALID");
        assertThat(paymentStatusOf(code)).isEqualTo("SUCCEEDED");
    }

    @Test
    void anOrderWithoutAPaymentToRefund_staysWhole_andDoesNotStopTheOthers() {
        Fixture f = fixture(10, 1);
        String broken = bookQuantity(f, f.userIds().get(0), 1);
        OrderPayments.pay(payableOrders, jdbc, broken);   // PAID with a ticket, but no payment row
        String good = paidQuantity(f, 1);
        cancelEvent(f);

        double failuresBefore = jobFailures(meters, "cancelled_event_orders");

        job.cancelOrdersOfCancelledEvents();

        assertThat(jobFailures(meters, "cancelled_event_orders")).as("the failed item is counted").isGreaterThan(failuresBefore);

        assertThat(statusOf(good)).isEqualTo("CANCELLED");
        assertThat(statusOf(broken)).as("rolled back, retried on the next run").isEqualTo("PAID");
        assertThat(ticketStatuses(broken)).containsExactly("VALID");
        stopRetrying(broken);
    }

    @Test
    void anOrderWithAUsedTicket_staysWhole() {
        Fixture f = fixture(10, 1);
        String code = paidQuantity(f, 2);
        jdbc.update("update tickets set status = 'USED', used_at = now() where id = (select min(t.id) from tickets t "
                + "join orders o on o.id = t.order_id where o.order_code = ?)", code);
        cancelEvent(f);

        job.cancelOrdersOfCancelledEvents();

        assertThat(statusOf(code)).isEqualTo("PAID");
        assertThat(ticketStatuses(code)).containsExactly("USED", "VALID");
        assertThat(paymentStatusOf(code)).isEqualTo("SUCCEEDED");
        stopRetrying(code);
    }

    @Test
    void severalJobsAtOnce_refundEachPaidOrderExactlyOnce() throws Exception {
        Fixture f = fixture(100, 1);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            codes.add(paidQuantity(f, 1));
        }
        cancelEvent(f);

        runJobsAtOnce(4);

        for (String code : codes) {
            assertThat(statusOf(code)).isEqualTo("CANCELLED");
            assertThat(paymentStatusOf(code)).isEqualTo("REFUND_PENDING");
            assertThat(jdbc.queryForObject("select p.version from payments p join orders o on o.id = p.order_id "
                    + "where o.order_code = ?", Long.class, code)).as("the payment moved once").isEqualTo(1);
        }
    }

    @Test
    void anUnpaidAuctionOrder_isLeftToTheAuctionModule() {
        Fixture f = fixture(10, 1);
        String auction = insertUnpaidOrder(f, "AUCTION", f.zoneDbId());
        String booking = insertUnpaidOrder(f, "BOOKING", f.zoneDbId());
        cancelEvent(f);

        job.cancelOrdersOfCancelledEvents();

        assertThat(statusOf(auction)).isEqualTo("PENDING_PAYMENT");
        assertThat(statusOf(booking)).isEqualTo("CANCELLED");
        assertThat(available(f)).as("only the booking order's ticket came back").isEqualTo(9);
    }

    @Test
    void anOrderThatCannotBeCancelled_doesNotStopTheOthersInItsBatch() {
        Fixture f = fixture(10, 1);
        long seatedZone = jdbc.queryForObject("select id from ticket_zones where public_id = ?", Long.class,
                f.seatedZoneId());
        String first = insertUnpaidOrder(f, "BOOKING", f.zoneDbId());
        // a standing item in a seated zone: there is no counter to give the ticket back to, so cancelling it fails
        String broken = insertUnpaidOrder(f, "BOOKING", seatedZone);
        String last = insertUnpaidOrder(f, "BOOKING", f.zoneDbId());
        cancelEvent(f);

        double failuresBefore = jobFailures(meters, "cancelled_event_orders");

        job.cancelOrdersOfCancelledEvents();

        assertThat(jobFailures(meters, "cancelled_event_orders")).as("the failed item is counted").isGreaterThan(failuresBefore);

        assertThat(statusOf(first)).isEqualTo("CANCELLED");
        assertThat(statusOf(last)).isEqualTo("CANCELLED");
        assertThat(statusOf(broken)).as("rolled back, retried on the next run").isEqualTo("PENDING_PAYMENT");
        assertThat(available(f)).isEqualTo(10);
        // otherwise the scheduled job keeps failing on it for as long as the tests run
        jdbc.update("update orders set status = 'CANCELLED' where order_code = ?", broken);
    }

    @Test
    void moreOrdersThanOneBatch_areAllCancelled() {
        int orders = 250;   // the job reads 100 at a time
        Fixture f = fixture(orders, 1);
        for (int i = 0; i < orders; i++) {
            bookQuantity(f, f.userIds().get(0), 1);
        }
        cancelEvent(f);

        job.cancelOrdersOfCancelledEvents();

        assertThat(jdbc.queryForObject("select count(*) from orders where event_id = "
                + "(select id from events where public_id = ?) and status = 'CANCELLED'", Integer.class, f.eventId()))
                .isEqualTo(orders);
        assertThat(available(f)).isEqualTo(orders);
    }

    @Test
    void stockThatCameBack_clearsTheGateFlags() {
        Fixture f = fixture(2, 1);
        UUID seat = f.seatedSeatIds().get(0);
        bookQuantity(f, f.userIds().get(0), 2);
        bookSeats(f, f.userIds().get(0), seat);
        gateway.setQuantityRejected(f.zoneId(), 1);
        gateway.setSeatFlagged(f.seatedZoneId(), List.of(seat));
        cancelEvent(f);

        job.cancelOrdersOfCancelledEvents();

        assertThat(gateway.mayTakeQuantity(f.zoneId(), 1)).isTrue();
        assertThat(gateway.flaggedSeats(f.seatedZoneId(), List.of(seat))).isEmpty();
    }

    @Test
    void severalJobsAtOnce_giveEachOrderBackExactlyOnce() throws Exception {
        int orders = 40;
        Fixture f = fixture(100, 1);
        for (int i = 0; i < orders; i++) {
            bookQuantity(f, f.userIds().get(0), 1);
        }
        // 50 more tickets are gone for good, so stock given back twice would still fit under the capacity and show here
        jdbc.update("update ticket_zones set available_quantity = available_quantity - 50 where id = ?", f.zoneDbId());
        cancelEvent(f);

        runJobsAtOnce(4);

        assertThat(available(f)).isEqualTo(50);
    }

    // ---- remembering that an event is done -------------------------------------------------

    @Test
    void anEventWithNoOpenOrderLeft_isMarkedCleaned_onceAuctionsAreDoneAndTheSettleWindowIsOver() {
        Fixture f = fixture(10, 1);
        String unpaid = bookQuantity(f, f.userIds().get(0), 1);
        String paid = paidQuantity(f, 1);
        cancelledAndAuctionsDone(f, 6);

        job.cancelOrdersOfCancelledEvents();

        assertThat(statusOf(unpaid)).isEqualTo("CANCELLED");
        assertThat(statusOf(paid)).isEqualTo("CANCELLED");
        assertThat(ordersMarkedCleaned(f)).isTrue();
    }

    @Test
    void anEventCancelledMomentsAgo_isNotMarkedYet_soALateOrderIsStillFound() {
        Fixture f = fixture(10, 1);
        cancelledAndAuctionsDone(f, 4);

        job.cancelOrdersOfCancelledEvents();

        assertThat(ordersMarkedCleaned(f)).isFalse();
    }

    @Test
    void anEventWhoseAuctionsAreNotCleanedUp_isNotMarked() {
        Fixture f = fixture(10, 1);
        // an auction awaiting payment without an open offer: the auction job leaves it, so its mark never comes
        jdbc.update("insert into auctions (event_id, zone_id, start_price, min_increment, starts_at, ends_at, status) "
                        + "select e.id, ?, 100000, 10000, now() - interval '2 hours', now() - interval '1 hour', "
                        + "'AWAITING_PAYMENT' from events e where e.public_id = ?", f.zoneDbId(), f.eventId());
        jdbc.update("update events set status = 'CANCELLED', cancelled_at = now() - interval '6 minutes' "
                + "where public_id = ?", f.eventId());

        job.cancelOrdersOfCancelledEvents();

        assertThat(ordersMarkedCleaned(f)).isFalse();
    }

    @Test
    void anEventWithAnOrderStillOpen_isNotMarked_untilThatOrderIsDealtWith() {
        Fixture f = fixture(10, 1);
        String auctionOrder = insertUnpaidOrder(f, "AUCTION", f.zoneDbId());   // not the booking job's to cancel
        cancelledAndAuctionsDone(f, 6);

        job.cancelOrdersOfCancelledEvents();
        assertThat(ordersMarkedCleaned(f)).isFalse();

        jdbc.update("update orders set status = 'CANCELLED' where order_code = ?", auctionOrder);
        job.cancelOrdersOfCancelledEvents();
        assertThat(ordersMarkedCleaned(f)).isTrue();
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void cancelledAndAuctionsDone(Fixture f, int minutesAgo) {
        jdbc.update("update events set status = 'CANCELLED', cancelled_at = now() - make_interval(mins => ?), "
                + "auctions_cleaned_at = now() where public_id = ?", minutesAgo, f.eventId());
    }

    private boolean ordersMarkedCleaned(Fixture f) {
        return jdbc.queryForObject("select orders_cleaned_at is not null from events where public_id = ?", Boolean.class,
                f.eventId());
    }

    private Fixture fixture(int capacity, int users) {
        return GeneralAdmissionFixtures.create(em, tx, capacity, users);
    }

    private void runJobsAtOnce(int jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < jobs; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                job.cancelOrdersOfCancelledEvents();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> run : runs) {
            run.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    /** A standing order paid through a VNPAY payment: PAID, its tickets issued, the payment SUCCEEDED. */
    private String paidQuantity(Fixture f, int quantity) {
        String code = bookQuantity(f, f.userIds().get(0), quantity);
        pay(code);
        return code;
    }

    private void pay(String orderCode) {
        OrderPayments.pay(payableOrders, jdbc, orderCode);
        succeededPayment(orderCode);
    }

    private void succeededPayment(String orderCode) {
        jdbc.update("insert into payments (order_id, provider, txn_ref, amount, status, requested_at) "
                        + "select id, 'VNPAY', ?, total_amount, 'SUCCEEDED', now() from orders where order_code = ?",
                UUID.randomUUID().toString().replace("-", ""), orderCode);
    }

    /** Takes an order that can never be refunded out of the job's way, so it does not fail on it for the rest of the run. */
    private void stopRetrying(String orderCode) {
        jdbc.update("update orders set status = 'CANCELLED' where order_code = ?", orderCode);
    }

    private List<String> ticketStatuses(String orderCode) {
        return jdbc.queryForList("select t.status from tickets t join orders o on o.id = t.order_id "
                + "where o.order_code = ? order by t.id", String.class, orderCode);
    }

    private String paymentStatusOf(String orderCode) {
        return jdbc.queryForObject("select p.status from payments p join orders o on o.id = p.order_id "
                + "where o.order_code = ?", String.class, orderCode);
    }

    private void cancelEvent(Fixture f) {
        jdbc.update("update events set status = 'CANCELLED', cancelled_at = now() where public_id = ?", f.eventId());
    }

    private String bookQuantity(Fixture f, UUID user, int quantity) {
        return bookingService.bookTicket(
                new BookingTicketRequest(f.eventId(), f.zoneId(), null, quantity), user, UUID.randomUUID().toString()).orderCode();
    }

    private String bookSeats(Fixture f, UUID user, UUID... seats) {
        return bookingService.bookTicket(
                new BookingTicketRequest(f.eventId(), f.seatedZoneId(), List.of(seats), null), user, UUID.randomUUID().toString()).orderCode();
    }

    /** An unpaid order with one standing ticket, inserted by SQL; the zone counter is lowered like a real hold would. */
    private String insertUnpaidOrder(Fixture f, String source, long zoneDbId) {
        long userId = jdbc.queryForObject("select id from users where public_id = ?", Long.class, f.userIds().get(0));
        long eventId = jdbc.queryForObject("select id from events where public_id = ?", Long.class, f.eventId());
        String code = "ORDER_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        long orderId = jdbc.queryForObject("insert into orders (user_id, event_id, source, total_amount, expires_at, "
                        + "order_code) values (?, ?, ?, 0, now() + interval '10 minutes', ?) returning id", Long.class,
                userId, eventId, source, code);
        jdbc.update("insert into order_items (order_id, zone_id, quantity, unit_price) values (?, ?, 1, 300000)",
                orderId, zoneDbId);
        if (zoneDbId == f.zoneDbId()) {
            jdbc.update("update ticket_zones set available_quantity = available_quantity - 1 where id = ?", zoneDbId);
        }
        return code;
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
