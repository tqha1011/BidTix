package com.bidtix.auction.service;

import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.infrastructure.AuctionClosingJob;
import com.bidtix.auction.infrastructure.AuctionOfferExpiryJob;
import com.bidtix.auction.service.AuctionFixtures.Fixture;
import com.bidtix.booking.domain.OrderPaidEvent;
import com.bidtix.booking.domain.OrderSource;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.service.EventService;
import com.bidtix.payment.service.PayableOrders;
import com.bidtix.payment.service.PayableOrders.Applied;
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
 * Paying an auction order: its offer is accepted and its auction settled (the listener of {@code OrderPaidEvent}), its seat is
 * sold and its ticket issued, all in the transaction that marks the order paid. Each test gets an auction that the real
 * closing job moved to AWAITING_PAYMENT.
 */
class AuctionSettlementTest extends PostgresIntegrationTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired AuctionClosingJob closingJob;
    @Autowired AuctionOfferExpiryJob expiryJob;
    @Autowired PayableOrders orders;
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

    // ---- paying through PayableOrders (the real flow) ------------------------------------------

    @Test
    void payingAStandingAuctionOrder_paysIt_acceptsTheOffer_settlesTheAuction_andIssuesTheTicket() {
        UUID auction = awaitingPayment(false);
        long order = orderOf(auction);
        int before = available(auction);

        assertThat(orders.applyPayment(order, Instant.now())).isEqualTo(Applied.APPLIED);

        assertThat(orderStatus(order)).isEqualTo("PAID");
        assertThat(offerStatus(auction)).isEqualTo("ACCEPTED");
        assertThat(statusOf(auction)).isEqualTo("SETTLED");
        assertThat(ticketsOf(order)).isEqualTo(1);
        assertThat(available(auction)).as("the ticket was taken off the counter when the auction was created").isEqualTo(before);
    }

    @Test
    void payingASeatedAuctionOrder_sellsTheReservedSeat_andIssuesTheTicket() {
        UUID auction = awaitingPayment(true);
        long order = orderOf(auction);
        assertThat(seatStatus(auction)).isEqualTo("RESERVED_FOR_AUCTION");

        assertThat(orders.applyPayment(order, Instant.now())).isEqualTo(Applied.APPLIED);

        assertThat(seatStatus(auction)).isEqualTo("SOLD");
        assertThat(orderStatus(order)).isEqualTo("PAID");
        assertThat(statusOf(auction)).isEqualTo("SETTLED");
        assertThat(ticketsOf(order)).isEqualTo(1);
    }

    @Test
    void payingACancelledOrder_changesNothing() {
        UUID auction = awaitingPayment(true);
        long order = orderOf(auction);
        jdbc.update("update orders set status = 'CANCELLED' where id = ?", order);

        assertThat(orders.applyPayment(order, Instant.now())).isEqualTo(Applied.NOT_PAYABLE);

        assertThat(offerStatus(auction)).isEqualTo("PENDING");
        assertThat(statusOf(auction)).isEqualTo("AWAITING_PAYMENT");
        assertThat(seatStatus(auction)).isEqualTo("RESERVED_FOR_AUCTION");
        assertThat(ticketsOf(order)).isZero();
    }

    @Test
    void aPaymentAfterTheOffersDeadline_isRolledBackEntirely_andTheJobThenCancelsAsUsual() {
        UUID auction = awaitingPayment(true);
        long order = orderOf(auction);
        jdbc.update("update auction_offers set expires_at = now() - interval '1 minute' where order_id = ?", order);

        assertThatThrownBy(() -> orders.applyPayment(order, Instant.now())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_PAYABLE));

        assertThat(orderStatus(order)).as("not left half paid").isEqualTo("PENDING_PAYMENT");
        assertThat(offerStatus(auction)).isEqualTo("PENDING");
        assertThat(seatStatus(auction)).isEqualTo("RESERVED_FOR_AUCTION");
        assertThat(ticketsOf(order)).isZero();

        expiryJob.expireOffers();

        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
        assertThat(offerStatus(auction)).isEqualTo("EXPIRED");
    }

    @Test
    void whenTheReservedSeatIsGone_thePaymentIsRolledBackEntirely() {
        UUID auction = awaitingPayment(true);
        long order = orderOf(auction);
        jdbc.update("update seats set status = 'AVAILABLE' where id = (select seat_id from auctions where public_id = ?)", auction);

        assertThatThrownBy(() -> orders.applyPayment(order, Instant.now())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_PAYABLE));

        assertThat(orderStatus(order)).isEqualTo("PENDING_PAYMENT");
        assertThat(offerStatus(auction)).as("the offer was accepted in the same transaction, so it is back").isEqualTo("PENDING");
        assertThat(statusOf(auction)).isEqualTo("AWAITING_PAYMENT");
        assertThat(ticketsOf(order)).isZero();
    }

    @Test
    void payingTwice_isAlreadyPaid_andIssuesNothingMore() {
        UUID auction = awaitingPayment(false);
        long order = orderOf(auction);
        assertThat(orders.applyPayment(order, Instant.now())).isEqualTo(Applied.APPLIED);

        assertThat(orders.applyPayment(order, Instant.now())).isEqualTo(Applied.ALREADY_PAID);

        assertThat(ticketsOf(order)).isEqualTo(1);
    }

    @Test
    void severalPaymentsAtOnce_payTheOrderOnce() throws Exception {
        UUID auction = awaitingPayment(true);
        long order = orderOf(auction);
        int callers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return orders.applyPayment(order, Instant.now()).name();
                } catch (BusinessException e) {
                    return e.getErrorCode().name();   // the loser whose offer was just accepted by the winner
                }
            }));
        }
        ready.await();
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(outcomes).containsOnlyOnce("APPLIED");
        assertThat(outcomes).allMatch(o -> List.of("APPLIED", "ALREADY_PAID", "ORDER_NOT_PAYABLE").contains(o));
        assertThat(ticketsOf(order)).isEqualTo(1);
        assertThat(seatStatus(auction)).isEqualTo("SOLD");
        assertThat(offerStatus(auction)).isEqualTo("ACCEPTED");
        assertThat(statusOf(auction)).isEqualTo("SETTLED");
        assertThat(orderStatus(order)).isEqualTo("PAID");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static OrderPaidEvent paid(long orderId, OrderSource source) {
        return new OrderPaidEvent(orderId, "ORDER_X", source, 500_000L, Instant.now());
    }

    private UUID awaitingPayment() {
        return awaitingPayment(false);
    }

    /**
     * An auction (of one seat, or of one standing ticket) with two bids, closed by the real closing job: AWAITING_PAYMENT
     * with a rank-1 offer and its order.
     */
    private UUID awaitingPayment(boolean seated) {
        Fixture f = AuctionFixtures.create(users, venues, events, zones, jdbc, 10, true);
        UUID auction = auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), seated ? f.seatedZoneId() : f.standingZoneId(), seated ? f.seatIds().get(0) : null,
                100_000L, 10_000L, null,
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

    private int ticketsOf(long order) {
        return jdbc.queryForObject("select count(*) from tickets where order_id = ?", Integer.class, order);
    }

    private int available(UUID auction) {
        return jdbc.queryForObject("select tz.available_quantity from ticket_zones tz join auctions a on a.zone_id = tz.id "
                + "where a.public_id = ?", Integer.class, auction);
    }

    private String seatStatus(UUID auction) {
        return jdbc.queryForObject("select s.status from seats s join auctions a on a.seat_id = s.id where a.public_id = ?",
                String.class, auction);
    }
}
