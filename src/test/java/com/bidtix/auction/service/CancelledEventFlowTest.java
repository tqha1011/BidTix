package com.bidtix.auction.service;

import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.infrastructure.AuctionClosingJob;
import com.bidtix.auction.infrastructure.CancelledEventAuctionJob;
import com.bidtix.auction.service.AuctionFixtures.Fixture;
import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.infrastructure.CancelledEventOrderJob;
import com.bidtix.booking.service.BookingService;
import com.bidtix.event.service.EventService;
import com.bidtix.event.service.VenueService;
import com.bidtix.event.service.ZoneService;
import com.bidtix.payment.service.PayableOrders;
import com.bidtix.support.PostgresIntegrationTest;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cancellation from start to end, through the real services and both jobs: an event with a paid order, an unpaid order
 * and an auction waiting for its winner to pay.
 */
class CancelledEventFlowTest extends PostgresIntegrationTest {

    @Autowired CancelledEventAuctionJob auctionJob;
    @Autowired CancelledEventOrderJob orderJob;
    @Autowired AuctionClosingJob closingJob;
    @Autowired AuctionService auctionService;
    @Autowired BookingService bookingService;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;
    @Autowired PayableOrders payableOrders;

    @Test
    void everythingSoldForACancelledEventIsUndone_andTheEventIsMarkedCleaned() {
        Fixture f = AuctionFixtures.create(users, venues, events, zones, jdbc, 10, true);
        // the sale opens tomorrow in the fixture: open it now
        jdbc.update("update events set sale_starts_at = now() - interval '1 hour' where public_id = ?", f.eventId());
        UUID auction = awaitingPayment(f);
        String paid = book(f, 2);
        pay(paid);
        String unpaid = book(f, 1);

        events.cancel(f.organizer().getPublicId(), f.eventId());
        auctionJob.cancelAuctionsOfCancelledEvents();
        orderJob.cancelOrdersOfCancelledEvents();

        assertThat(jdbc.queryForObject("select status from auctions where public_id = ?", String.class, auction))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForList("select o.status from orders o join events e on e.id = o.event_id "
                + "where e.public_id = ?", String.class, f.eventId())).hasSize(3).containsOnly("CANCELLED");
        assertThat(jdbc.queryForList("select t.status from tickets t join orders o on o.id = t.order_id "
                + "where o.order_code = ?", String.class, paid)).containsExactly("CANCELLED", "CANCELLED");
        assertThat(jdbc.queryForList("select p.status from payments p join orders o on o.id = p.order_id "
                + "where o.order_code in (?, ?)", String.class, paid, unpaid)).containsExactly("REFUND_PENDING");
        assertThat(marks(f)).as("too early to call it done").containsExactly(false, false);

        jdbc.update("update events set cancelled_at = now() - interval '6 minutes' where public_id = ?", f.eventId());
        auctionJob.cancelAuctionsOfCancelledEvents();
        orderJob.cancelOrdersOfCancelledEvents();

        assertThat(marks(f)).containsExactly(true, true);
    }

    private UUID awaitingPayment(Fixture f) {
        Instant now = Instant.now();
        UUID auction = auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), f.standingZoneId(), null, 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), null, null)).publicId();
        User winner = customer();
        jdbc.update("insert into bids (auction_id, bidder_id, amount) select id, ?, 120000 from auctions "
                + "where public_id = ?", winner.getId(), auction);
        jdbc.update("update auctions set status = 'LIVE', starts_at = now() - interval '2 hours', "
                + "ends_at = now() - interval '1 minute', current_price = 120000, leading_bidder_id = ?, bid_count = 1 "
                + "where public_id = ?", winner.getId(), auction);
        closingJob.closeAuction();
        return auction;
    }

    private User customer() {
        return users.save(new User("customer-" + UUID.randomUUID() + "@test.dev", "hash", "Customer", UserRole.CUSTOMER));
    }

    private String book(Fixture f, int quantity) {
        return bookingService.bookTicket(new BookingTicketRequest(f.eventId(), f.standingZoneId(), null, quantity),
                customer().getPublicId()).orderCode();
    }

    private void pay(String orderCode) {
        Long orderId = jdbc.queryForObject("select id from orders where order_code = ?", Long.class, orderCode);
        payableOrders.applyPayment(orderId, Instant.now());
        jdbc.update("insert into payments (order_id, provider, txn_ref, amount, status, requested_at) "
                        + "select id, 'VNPAY', ?, total_amount, 'SUCCEEDED', now() from orders where id = ?",
                UUID.randomUUID().toString().replace("-", ""), orderId);
    }

    /** Whether the auction and the booking module have marked the event, in that order. */
    private List<Boolean> marks(Fixture f) {
        return jdbc.queryForObject("select auctions_cleaned_at is not null, orders_cleaned_at is not null from events "
                        + "where public_id = ?",
                (row, n) -> List.of(row.getBoolean(1), row.getBoolean(2)), f.eventId());
    }
}
