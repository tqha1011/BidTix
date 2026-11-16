package com.bidtix.booking.service;

import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.booking.domain.TicketStatus;
import com.bidtix.booking.dtos.BaseBookingTicketResult;
import com.bidtix.booking.dtos.BookingTicketQuantityResult;
import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.dtos.AdminTicketResponse;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Booking of GENERAL_ADMISSION (standing) zones, one request at a time. Parallel requests: see the concurrency test. */
class GeneralAdmissionBookingTest extends PostgresIntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired AdminTicketService adminTicketService;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    void booking_takesTheQuantity_chargesPerUnit_andIssuesOneTicketPerUnit() {
        Fixture f = fixture(10, 1);

        BaseBookingTicketResult result = book(f, f.userIds().get(0), 3);

        assertThat(result).isInstanceOf(BookingTicketQuantityResult.class);
        assertThat(result.status()).isEqualTo(OrderStatus.PAID);
        assertThat(result.zoneType()).isEqualTo(ZoneType.GENERAL_ADMISSION);
        assertThat(result.totalAmount()).isEqualTo(3 * f.price());
        assertThat(result.tickets()).hasSize(3);
        assertThat(((BookingTicketQuantityResult) result).quantity()).isEqualTo(3);

        assertThat(available(f)).as("available_quantity").isEqualTo(7);
        assertThat(count("select count(*) from tickets where zone_id = ? and seat_id is null", f.zoneDbId())).isEqualTo(3);
        assertThat(count("select count(distinct order_item_id) from tickets where zone_id = ?", f.zoneDbId()))
                .as("all tickets point at one order item").isEqualTo(1);
        assertThat(jdbc.queryForObject("select quantity from order_items where zone_id = ?", Integer.class, f.zoneDbId()))
                .isEqualTo(3);

        // standing tickets have no seat: the admin list must still show them (LEFT JOIN on seats)
        List<AdminTicketResponse> listed = adminTicketService.listForEvent(f.eventId(), TicketStatus.VALID, 0, 20).items();
        assertThat(listed).hasSize(3).allSatisfy(t -> {
            assertThat(t.rowLabel()).isNull();
            assertThat(t.seatNumber()).isNull();
            assertThat(t.zoneName()).isEqualTo("Standing");
        });
    }

    @Test
    void exactlyTheRemainingStockCanBeBought_thenTheZoneIsSoldOut_andLeavesNoOrderBehind() {
        Fixture f = fixture(5, 3);
        book(f, f.userIds().get(0), 3);
        book(f, f.userIds().get(1), 2);
        assertThat(available(f)).isZero();
        int ordersBefore = orders(f);

        assertThatThrownBy(() -> book(f, f.userIds().get(2), 1))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZONE_SOLD_OUT);
                    assertThat(e.getProperties()).containsEntry("availableQuantity", 0);
                });

        assertThat(orders(f)).as("the failed attempt rolled back its order").isEqualTo(ordersBefore);
        assertThat(count("select count(*) from tickets where zone_id = ?", f.zoneDbId())).isEqualTo(5);
    }

    @Test
    void askingForMoreThanIsLeft_isRejected_andTakesNothing() {
        Fixture f = fixture(4, 2);
        book(f, f.userIds().get(0), 3);

        assertThatThrownBy(() -> book(f, f.userIds().get(1), 2))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZONE_SOLD_OUT);
                    assertThat(e.getProperties()).containsEntry("availableQuantity", 1);
                });
        assertThat(available(f)).isEqualTo(1);
    }

    @Test
    void invalidRequests_areRejected_andTakeNothing() {
        Fixture f = fixture(10, 1);
        UUID user = f.userIds().get(0);

        expectInvalid(request(f, f.zoneId(), null, null), user);                         // no quantity
        expectInvalid(request(f, f.zoneId(), null, 0), user);                            // quantity 0
        expectInvalid(request(f, f.zoneId(), null, -2), user);                           // negative
        expectInvalid(request(f, f.zoneId(), null, 6), user);                            // over the limit of 5
        expectInvalid(request(f, f.zoneId(), List.of(UUID.randomUUID()), 1), user);      // seats sent for a GA zone
        expectInvalid(request(f, f.seatedZoneId(), f.seatedSeatIds(), null), user);      // 6 seats, limit is 5
        expectInvalid(request(f, f.seatedZoneId(), null, 2), user);                      // quantity for a SEATED zone

        assertThat(available(f)).isEqualTo(10);
        assertThat(orders(f)).isZero();
    }

    @Test
    void fiveTicketsAreAllowed() {
        Fixture f = fixture(10, 1);

        assertThat(book(f, f.userIds().get(0), 5).tickets()).hasSize(5);
        assertThat(available(f)).isEqualTo(5);
    }

    @Test
    void aZoneOfAnotherEvent_isNotFound() {
        Fixture f = fixture(10, 1);

        assertThatThrownBy(() -> bookingService.bookTicket(
                request(f, f.otherEventZoneId(), null, 1), f.userIds().get(0)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZONE_NOTFOUND));
        assertThat(orders(f)).isZero();
    }

    // ---- helpers ---------------------------------------------------------------------------

    private Fixture fixture(int capacity, int users) {
        return GeneralAdmissionFixtures.create(em, tx, capacity, users);
    }

    private BaseBookingTicketResult book(Fixture f, UUID user, int quantity) {
        return bookingService.bookTicket(request(f, f.zoneId(), null, quantity), user);
    }

    private static BookingTicketRequest request(Fixture f, UUID zone, List<UUID> seats, Integer quantity) {
        return new BookingTicketRequest(f.eventId(), zone, seats, quantity);
    }

    private void expectInvalid(BookingTicketRequest request, UUID user) {
        assertThatThrownBy(() -> bookingService.bookTicket(request, user))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    private int available(Fixture f) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where id = ?", Integer.class, f.zoneDbId());
    }

    private int orders(Fixture f) {
        return count("select count(*) from orders o join events e on e.id = o.event_id where e.public_id = ?", f.eventId());
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }
}
