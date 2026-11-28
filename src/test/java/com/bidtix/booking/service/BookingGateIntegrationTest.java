package com.bidtix.booking.service;

import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.booking.dtos.BaseBookingTicketResult;
import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.infrastructure.BookingGateway;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
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

/**
 * The booking gate against the real application, PostgreSQL and Redis. Three things are checked:
 * the database's "no" is remembered, a remembered "no" is answered without the database, and a smaller request
 * still reaches the database. To tell "the gate answered" from "the database answered": a database refusal of a
 * standing quantity carries {@code availableQuantity}, a gate refusal does not.
 */
class BookingGateIntegrationTest extends PostgresIntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired BookingGateway gateway;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    // ---- standing zones --------------------------------------------------------------------

    @Test
    void aDatabaseSoldOut_isRemembered_andSmallerRequestsStillPass() {
        Fixture f = fixture(2, 2);

        assertThatThrownBy(() -> bookQuantity(f, f.userIds().get(0), 3))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZONE_SOLD_OUT);
                    assertThat(e.getProperties()).as("the database answered").containsEntry("availableQuantity", 2);
                });

        assertThat(gateway.mayTakeQuantity(f.zoneId(), 3)).isFalse();
        assertThat(gateway.mayTakeQuantity(f.zoneId(), 4)).isFalse();
        assertThat(gateway.mayTakeQuantity(f.zoneId(), 2)).isTrue();
        assertThat(bookQuantity(f, f.userIds().get(1), 2).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void aFlag_isAnsweredWithoutTheDatabase_atTheExactQuantity_andAbove() {
        Fixture f = fixture(10, 3);
        gateway.setQuantityRejected(f.zoneId(), 3);

        // the database has 10 left, so any answer of "sold out" came from the flag
        for (int quantity : new int[]{3, 4}) {
            assertThatThrownBy(() -> bookQuantity(f, f.userIds().get(0), quantity))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZONE_SOLD_OUT);
                        assertThat(e.getProperties()).as("the gate answered").doesNotContainKey("availableQuantity");
                    });
        }
        assertThat(orders(f)).as("no order was even inserted").isZero();
        assertThat(available(f)).isEqualTo(10);

        assertThat(bookQuantity(f, f.userIds().get(1), 2).status()).as("below the flag").isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(available(f)).isEqualTo(8);
    }

    @Test
    void aFlag_expiresOnItsOwn_andTheDatabaseDecidesAgain() throws InterruptedException {
        Fixture f = fixture(10, 1);
        gateway.setQuantityRejected(f.zoneId(), 3);   // bidtix.booking.gate-ttl-seconds = 3 by default
        assertThatThrownBy(() -> bookQuantity(f, f.userIds().get(0), 3)).isInstanceOf(BusinessException.class);

        long deadline = System.nanoTime() + 6_000_000_000L;
        while (!gateway.mayTakeQuantity(f.zoneId(), 3) && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }

        assertThat(gateway.mayTakeQuantity(f.zoneId(), 3)).as("flag gone").isTrue();
        assertThat(bookQuantity(f, f.userIds().get(0), 3).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    // ---- seats -----------------------------------------------------------------------------

    @Test
    void aSeatTheDatabaseRefused_isRemembered_andOnlyThatSeatIsBlocked() {
        Fixture f = fixture(10, 3);
        UUID taken = f.seatedSeatIds().get(0);
        UUID free = f.seatedSeatIds().get(1);

        assertThat(bookSeats(f, f.userIds().get(0), taken).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThatThrownBy(() -> bookSeats(f, f.userIds().get(1), taken, free))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SEAT_UNAVAILABLE));

        assertThat(gateway.flaggedSeats(f.seatedZoneId(), List.of(taken, free))).containsExactly(taken);
        // the same seat again: refused by the gate, and the free seat next to it is still bookable
        assertThatThrownBy(() -> bookSeats(f, f.userIds().get(2), taken))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SEAT_UNAVAILABLE);
                    assertThat(e.getProperties()).containsEntry("seatPublicIds", List.of(taken));
                });
        assertThat(bookSeats(f, f.userIds().get(2), free).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void aFlaggedSeat_isRefusedWithoutTheDatabase() {
        Fixture f = fixture(10, 2);
        UUID seat = f.seatedSeatIds().get(2);
        gateway.setSeatFlagged(f.seatedZoneId(), List.of(seat));   // free in the database

        assertThatThrownBy(() -> bookSeats(f, f.userIds().get(0), seat))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SEAT_UNAVAILABLE));
        assertThat(orders(f)).as("no order was even inserted").isZero();

        assertThat(bookSeats(f, f.userIds().get(1), f.seatedSeatIds().get(3)).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void aSeatFlag_isScopedToItsZone() {
        UUID seat = UUID.randomUUID();
        UUID zoneA = UUID.randomUUID();
        UUID zoneB = UUID.randomUUID();

        gateway.setSeatFlagged(zoneA, List.of(seat));

        assertThat(gateway.flaggedSeats(zoneA, List.of(seat))).containsExactly(seat);
        assertThat(gateway.flaggedSeats(zoneB, List.of(seat))).isEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------

    private Fixture fixture(int capacity, int users) {
        return GeneralAdmissionFixtures.create(em, tx, capacity, users);
    }

    private BaseBookingTicketResult bookQuantity(Fixture f, UUID user, int quantity) {
        return bookingService.bookTicket(new BookingTicketRequest(f.eventId(), f.zoneId(), null, quantity), user, UUID.randomUUID().toString());
    }

    private BaseBookingTicketResult bookSeats(Fixture f, UUID user, UUID... seats) {
        return bookingService.bookTicket(
                new BookingTicketRequest(f.eventId(), f.seatedZoneId(), List.of(seats), null), user, UUID.randomUUID().toString());
    }

    private int available(Fixture f) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where id = ?", Integer.class, f.zoneDbId());
    }

    private int orders(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from orders o join events e on e.id = o.event_id where e.public_id = ?",
                Integer.class, f.eventId());
    }
}
