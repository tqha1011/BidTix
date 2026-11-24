package com.bidtix.booking.service;

import com.bidtix.booking.domain.TicketStatus;
import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.dtos.AdminTicketResponse;
import com.bidtix.common.dtos.PageResponse;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminTicketServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired AdminTicketService adminTicketService;
    @Autowired BookingService bookingService;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PayableOrders payableOrders;

    /** An event with 5 seats whose tickets were all bought, each by a different customer. */
    private record Sold(UUID eventId, List<UUID> ticketIds, List<String> ownerEmails) {
    }

    @Test
    void listsOnlyThisEventsTickets_withSeatZoneAndOwner_inPages() {
        Sold mine = sellTickets(5);
        Sold other = sellTickets(3);

        PageResponse<AdminTicketResponse> first = adminTicketService.listForEvent(mine.eventId(), null, 0, 2);
        PageResponse<AdminTicketResponse> last = adminTicketService.listForEvent(mine.eventId(), null, 2, 2);

        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.items()).hasSize(2);
        assertThat(last.items()).hasSize(1);

        AdminTicketResponse row = first.items().get(0);
        assertThat(row.status()).isEqualTo(TicketStatus.VALID);
        assertThat(row.zoneName()).isEqualTo("Zone A");
        assertThat(row.rowLabel()).isEqualTo("A");
        assertThat(row.seatNumber()).isNotNull();
        assertThat(row.orderCode()).isNotBlank();
        assertThat(mine.ownerEmails()).contains(row.ownerEmail());

        List<UUID> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            adminTicketService.listForEvent(mine.eventId(), null, page, 2).items()
                    .forEach(t -> seen.add(t.ticketPublicId()));
        }
        assertThat(seen).as("every ticket exactly once").containsExactlyInAnyOrderElementsOf(mine.ticketIds());
        assertThat(seen).doesNotContainAnyElementsOf(other.ticketIds());
    }

    @Test
    void statusFilter_returnsOnlyThatStatus() {
        Sold sold = sellTickets(3);
        jdbc.update("update tickets set status = 'USED', used_at = now() where public_id = ?", sold.ticketIds().get(0));

        assertThat(adminTicketService.listForEvent(sold.eventId(), TicketStatus.USED, 0, 20).items())
                .extracting(AdminTicketResponse::ticketPublicId).containsExactly(sold.ticketIds().get(0));
        assertThat(adminTicketService.listForEvent(sold.eventId(), TicketStatus.VALID, 0, 20).totalElements())
                .isEqualTo(2);
        assertThat(adminTicketService.listForEvent(sold.eventId(), TicketStatus.CANCELLED, 0, 20).items()).isEmpty();
    }

    @Test
    void badPaging_isCorrected() {
        Sold sold = sellTickets(2);

        PageResponse<AdminTicketResponse> page = adminTicketService.listForEvent(sold.eventId(), null, -3, 100_000);

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(AdminTicketService.MAX_PAGE_SIZE);
        assertThat(page.items()).hasSize(2);
    }

    @Test
    void unknownEvent_isEventNotFound() {
        assertThatThrownBy(() -> adminTicketService.listForEvent(UUID.randomUUID(), null, 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOTFOUND));
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Creates an event with {@code count} seats and has a different customer book and pay for each one. */
    private Sold sellTickets(int count) {
        String run = UUID.randomUUID().toString().substring(0, 8);
        record Setup(UUID eventId, UUID zoneId, List<UUID> seatIds, List<UUID> userIds, List<String> emails) {
        }
        Setup setup = tx.execute(s -> {
            User organizer = new User("org-" + run + "@test.dev", "hash", "Organizer", UserRole.ORGANIZER);
            em.persist(organizer);
            Venue venue = new Venue("Arena " + run, "1 Street", "Hanoi");
            em.persist(venue);
            Instant now = Instant.now();
            Event event = new Event(organizer.getId(), venue, "Concert " + run, null,
                    now.plus(30, ChronoUnit.DAYS), now.plus(31, ChronoUnit.DAYS),
                    now.minus(1, ChronoUnit.DAYS), now.plus(29, ChronoUnit.DAYS), 10);
            em.persist(event);
            TicketZone zone = new TicketZone(event, "Zone A", ZoneType.SEATED, 500_000, count);
            em.persist(zone);
            List<UUID> seatIds = new ArrayList<>();
            List<UUID> userIds = new ArrayList<>();
            List<String> emails = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                Seat seat = new Seat(zone, "A", i);
                em.persist(seat);
                seatIds.add(seat.getPublicId());
                User user = new User("buyer" + i + "-" + run + "@test.dev", "hash", "Buyer " + i, UserRole.CUSTOMER);
                em.persist(user);
                userIds.add(user.getPublicId());
                emails.add(user.getEmail());
            }
            em.flush();
            return new Setup(event.getPublicId(), zone.getPublicId(), seatIds, userIds, emails);
        });

        List<UUID> ticketIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var result = bookingService.bookTicket(new BookingTicketRequest(setup.eventId(), setup.zoneId(),
                    List.of(setup.seatIds().get(i)), null), setup.userIds().get(i));
            OrderPayments.pay(payableOrders, jdbc, result.orderCode());   // booking only holds the seat: paying issues the ticket
            ticketIds.addAll(jdbc.queryForList("select t.public_id from tickets t join orders o on o.id = t.order_id "
                    + "where o.order_code = ?", UUID.class, result.orderCode()));
        }
        return new Sold(setup.eventId(), ticketIds, setup.emails());
    }
}
