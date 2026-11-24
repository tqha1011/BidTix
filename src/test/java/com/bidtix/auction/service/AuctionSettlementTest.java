package com.bidtix.auction.service;

import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.infrastructure.AuctionClosingJob;
import com.bidtix.auction.service.AuctionFixtures.Fixture;
import com.bidtix.booking.domain.OrderPaidEvent;
import com.bidtix.booking.domain.OrderSource;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.service.EventService;
import com.bidtix.event.service.VenueService;
import com.bidtix.event.service.ZoneService;
import com.bidtix.support.PostgresIntegrationTest;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The listener of {@code OrderPaidEvent}: when an auction order is paid, its offer is accepted and its auction settled, in
 * the transaction that marked the order paid. Each test gets an auction that the real closing job moved to AWAITING_PAYMENT.
 */
class AuctionSettlementTest extends PostgresIntegrationTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired AuctionClosingJob closingJob;
    @Autowired AuctionService auctionService;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    @Test
    void aPaidAuctionOrder_acceptsItsOffer_andSettlesTheAuction() {
        UUID auction = awaitingPayment();
        long order = orderOf(auction);

        tx.executeWithoutResult(status -> publisher.publishEvent(paid(order, OrderSource.AUCTION)));

        assertThat(offerStatus(auction)).isEqualTo("ACCEPTED");
        assertThat(statusOf(auction)).isEqualTo("SETTLED");
    }

    @Test
    void anOrderThatIsNotAnAuctionOrder_isIgnored() {
        UUID auction = awaitingPayment();
        long order = orderOf(auction);

        tx.executeWithoutResult(status -> publisher.publishEvent(paid(order, OrderSource.BOOKING)));

        assertThat(offerStatus(auction)).isEqualTo("PENDING");
        assertThat(statusOf(auction)).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void anOfferPastItsDeadline_cannotBeAccepted_andTheWholeTransactionRollsBack() {
        UUID auction = awaitingPayment();
        long order = orderOf(auction);
        jdbc.update("update auction_offers set expires_at = now() - interval '1 minute' where order_id = ?", order);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("update orders set status = 'PAID', paid_at = now() where id = ?", order);   // what the payment did
            publisher.publishEvent(paid(order, OrderSource.AUCTION));
        })).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_PAYABLE));

        assertThat(offerStatus(auction)).isEqualTo("PENDING");
        assertThat(statusOf(auction)).isEqualTo("AWAITING_PAYMENT");
        assertThat(orderStatus(order)).as("the payment rolled back with it, in the same transaction").isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void anOfferThatWasAlreadyAccepted_isNotAcceptedTwice() {
        UUID auction = awaitingPayment();
        long order = orderOf(auction);
        tx.executeWithoutResult(status -> publisher.publishEvent(paid(order, OrderSource.AUCTION)));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> publisher.publishEvent(paid(order, OrderSource.AUCTION))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_PAYABLE));
        assertThat(statusOf(auction)).isEqualTo("SETTLED");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static OrderPaidEvent paid(long orderId, OrderSource source) {
        return new OrderPaidEvent(orderId, "ORDER_X", source, 500_000L, Instant.now());
    }

    /** A standing auction with two bids, closed by the real closing job: AWAITING_PAYMENT with a rank-1 offer and its order. */
    private UUID awaitingPayment() {
        Fixture f = AuctionFixtures.create(users, venues, events, zones, jdbc, 10, true);
        UUID auction = auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), f.standingZoneId(), null, 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), null, null)).publicId();
        User winner = bidder();
        jdbc.update("insert into bids (auction_id, bidder_id, amount) select id, ?, 500000 from auctions where public_id = ?",
                winner.getId(), auction);
        jdbc.update("insert into bids (auction_id, bidder_id, amount) select id, ?, 450000 from auctions where public_id = ?",
                bidder().getId(), auction);
        jdbc.update("update auctions set status = 'LIVE', starts_at = now() - interval '2 hours', "
                + "ends_at = now() - interval '1 minute', current_price = 500000, leading_bidder_id = ?, bid_count = 2 "
                + "where public_id = ?", winner.getId(), auction);
        closingJob.closeAuction();
        assertThat(statusOf(auction)).as("set-up: the closing job awarded it").isEqualTo("AWAITING_PAYMENT");
        return auction;
    }

    private User bidder() {
        return users.save(new User("bidder-" + UUID.randomUUID() + "@test.dev", "hash", "Bidder", UserRole.CUSTOMER));
    }

    private long orderOf(UUID auction) {
        return jdbc.queryForObject("select ao.order_id from auction_offers ao join auctions a on a.id = ao.auction_id "
                + "where a.public_id = ? and ao.offer_rank = 1", Long.class, auction);
    }

    private String offerStatus(UUID auction) {
        return jdbc.queryForObject("select ao.status from auction_offers ao join auctions a on a.id = ao.auction_id "
                + "where a.public_id = ? and ao.offer_rank = 1", String.class, auction);
    }

    private String statusOf(UUID auction) {
        return jdbc.queryForObject("select status from auctions where public_id = ?", String.class, auction);
    }

    private String orderStatus(long order) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, order);
    }
}
