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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * The same booking sent again with the same key, as happens when {@code idempotency_keys} no longer knows it (the server
 * died after the booking committed, or the response could not be stored): the service is called directly, without the
 * filter that would normally replay the response.
 */
class IdempotentBookingTest extends PostgresIntegrationTest {

    @Autowired BookingService service;
    @Autowired PayableOrders payableOrders;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    @Test
    void aRepeatedStandingBooking_returnsTheFirstOrder_andTakesNothingMore() {
        Fixture f = fixture(10, 1);
        BookingTicketRequest request = standing(f, 2);
        String key = key();

        BaseBookingTicketResult first = service.bookTicket(request, user(f), key);
        BaseBookingTicketResult again = service.bookTicket(request, user(f), key);

        assertThat(again.orderCode()).isEqualTo(first.orderCode());
        assertThat(orders(f)).isEqualTo(1);
        assertThat(available(f)).isEqualTo(8);
    }

    @Test
    void aRepeatedSeatBooking_returnsTheFirstOrder_insteadOfSayingItsOwnSeatsAreTaken() {
        Fixture f = fixture(10, 1);
        BookingTicketRequest request = seats(f, 1, 0);
        String key = key();

        BaseBookingTicketResult first = service.bookTicket(request, user(f), key);
        BaseBookingTicketResult again = service.bookTicket(seats(f, 0, 1), user(f), key);   // the same seats, other order

        assertThat(again.orderCode()).isEqualTo(first.orderCode());
        assertThat(orders(f)).isEqualTo(1);
    }

    @Test
    void aRepeatAfterTheLastTicketsWereTaken_stillGetsItsOrder() {
        Fixture f = fixture(2, 2);
        BookingTicketRequest request = standing(f, 2);
        String key = key();
        BaseBookingTicketResult first = service.bookTicket(request, user(f), key);
        // somebody else learns that the zone is sold out, so the booking gate now refuses without asking the database
        assertThatThrownBy(() -> service.bookTicket(standing(f, 2), f.userIds().get(1), key()))
                .isInstanceOf(BusinessException.class);

        assertThat(service.bookTicket(request, user(f), key).orderCode()).isEqualTo(first.orderCode());
    }

    @Test
    void aRepeatAtTheTicketLimit_stillGetsItsOrder() {
        Fixture f = fixture(10, 1);
        limitTicketsPerUser(f, 3);
        BookingTicketRequest request = standing(f, 3);
        String key = key();
        BaseBookingTicketResult first = service.bookTicket(request, user(f), key);

        assertThat(service.bookTicket(request, user(f), key).orderCode()).isEqualTo(first.orderCode());
    }

    @Test
    void aRepeatAfterTheSaleEnded_stillGetsItsOrder() {
        Fixture f = fixture(10, 1);
        BookingTicketRequest request = standing(f, 1);
        String key = key();
        BaseBookingTicketResult first = service.bookTicket(request, user(f), key);
        jdbc.update("update events set sale_starts_at = now() - interval '2 days', "
                + "sale_ends_at = now() - interval '1 day' where public_id = ?", f.eventId());

        assertThat(service.bookTicket(request, user(f), key).orderCode()).isEqualTo(first.orderCode());
    }

    @Test
    void aRepeatAfterPaying_showsThePaidOrderWithItsTickets() {
        Fixture f = fixture(10, 1);
        BookingTicketRequest request = standing(f, 2);
        String key = key();
        BaseBookingTicketResult first = service.bookTicket(request, user(f), key);
        OrderPayments.pay(payableOrders, jdbc, first.orderCode());

        BaseBookingTicketResult again = service.bookTicket(request, user(f), key);

        assertThat(again.status().name()).isEqualTo("PAID");
        assertThat(again.tickets()).hasSize(2);
    }

    /** Whatever differs, the answer is "this key was used for another request": never the old order, never a crash. */
    @ParameterizedTest
    @ValueSource(strings = {"another quantity", "no quantity", "seats instead of a quantity", "another zone"})
    void theSameKeyForAnotherStandingRequest_isRefused(String change) {
        Fixture f = fixture(10, 1);
        String key = key();
        service.bookTicket(standing(f, 2), user(f), key);
        BookingTicketRequest other = switch (change) {
            case "another quantity" -> standing(f, 3);
            case "no quantity" -> new BookingTicketRequest(f.eventId(), f.zoneId(), null, null);
            case "seats instead of a quantity" ->
                    new BookingTicketRequest(f.eventId(), f.zoneId(), List.of(f.seatedSeatIds().get(0)), 2);
            default -> seats(f, 0);
        };

        assertRefusedAsReused(() -> service.bookTicket(other, user(f), key));
        assertThat(orders(f)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"other seats", "one seat fewer", "a seat twice", "no seats"})
    void theSameKeyForAnotherSeatRequest_isRefused(String change) {
        Fixture f = fixture(10, 1);
        String key = key();
        service.bookTicket(seats(f, 0, 1), user(f), key);
        BookingTicketRequest other = switch (change) {
            case "other seats" -> seats(f, 0, 2);
            case "one seat fewer" -> seats(f, 0);
            case "a seat twice" -> seats(f, 0, 1, 1);
            default -> new BookingTicketRequest(f.eventId(), f.seatedZoneId(), null, 2);
        };

        assertRefusedAsReused(() -> service.bookTicket(other, user(f), key));
        assertThat(orders(f)).isEqualTo(1);
    }

    @Test
    void aKeyBelongsToItsUser() {
        Fixture f = fixture(10, 2);
        String key = key();

        String mine = service.bookTicket(standing(f, 1), f.userIds().get(0), key).orderCode();
        String theirs = service.bookTicket(standing(f, 1), f.userIds().get(1), key).orderCode();

        assertThat(theirs).isNotEqualTo(mine);
    }

    @ParameterizedTest
    @ValueSource(strings = {"standing", "seated"})
    void theSameBookingSentManyTimesAtOnce_makesOneOrder(String kind) throws Exception {
        for (int round = 0; round < 5; round++) {
            Fixture f = fixture(20, 1);
            BookingTicketRequest request = kind.equals("standing") ? standing(f, 2) : seats(f, 0, 1);
            String key = key();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch go = new CountDownLatch(1);
            List<String> codes = new ArrayList<>();
            try {
                List<Future<String>> calls = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    calls.add(pool.submit(() -> {
                        go.await();
                        return service.bookTicket(request, user(f), key).orderCode();
                    }));
                }
                go.countDown();
                for (Future<String> call : calls) {
                    codes.add(call.get(30, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(codes).as("round %s", round).hasSize(8).containsOnly(codes.getFirst());
            assertThat(orders(f)).isEqualTo(1);
            if (kind.equals("standing")) {
                assertThat(available(f)).isEqualTo(18);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static void assertRefusedAsReused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    private Fixture fixture(int capacity, int users) {
        return GeneralAdmissionFixtures.create(em, tx, capacity, users);
    }

    private static UUID user(Fixture f) {
        return f.userIds().get(0);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static BookingTicketRequest standing(Fixture f, int quantity) {
        return new BookingTicketRequest(f.eventId(), f.zoneId(), null, quantity);
    }

    private static BookingTicketRequest seats(Fixture f, int... indexes) {
        List<UUID> ids = new ArrayList<>();
        for (int index : indexes) {
            ids.add(f.seatedSeatIds().get(index));
        }
        return new BookingTicketRequest(f.eventId(), f.seatedZoneId(), ids, null);
    }

    private void limitTicketsPerUser(Fixture f, int limit) {
        jdbc.update("update events set max_tickets_per_user = ? where public_id = ?", limit, f.eventId());
    }

    private int orders(Fixture f) {
        return jdbc.queryForObject("select count(*) from orders o join events e on e.id = o.event_id "
                + "where e.public_id = ?", Integer.class, f.eventId());
    }

    private int available(Fixture f) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where public_id = ?", Integer.class,
                f.zoneId());
    }
}
