package com.bidtix.auction.service;

import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.infrastructure.AuctionClosingJob;
import com.bidtix.auction.infrastructure.AuctionOfferExpiryJob;
import com.bidtix.auction.service.AuctionFixtures.Fixture;
import com.bidtix.booking.infrastructure.BookingGateway;
import com.bidtix.event.service.EventService;
import com.bidtix.event.service.VenueService;
import com.bidtix.event.service.ZoneService;
import com.bidtix.support.PostgresIntegrationTest;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The job that deals with offers nobody paid in time: the unpaid order is cancelled and the right to buy passes to the
 * next-highest bidder, or the auction fails and its ticket goes back on sale. If the order was paid at the last moment
 * the offer is accepted instead. The auctions are closed by the real closing job first, so each test starts from what
 * production would have produced. The application runs both jobs every minute, so the tests only look at their own
 * auctions and never at what the jobs return.
 */
class AuctionOfferExpiryJobTest extends PostgresIntegrationTest {

    @Autowired AuctionOfferExpiryJob job;
    @Autowired AuctionClosingJob closingJob;
    @Autowired AuctionService auctionService;
    @Autowired BookingGateway gateway;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    /** One accepted bid. */
    private record Bidding(User by, long amount) {
    }

    // ---- the right to buy passes on --------------------------------------------------------------

    @Test
    void anUnpaidOffer_passesToTheNextBidder_atTheirOwnBid() {
        Fixture f = fixture(10);
        UUID seat = f.seatIds().get(0);
        User ann = bidder();
        User ben = bidder();
        UUID auction = awaitingPayment(seated(f, seat, null), new Bidding(ann, 500_000), new Bidding(ben, 450_000));
        Object firstOrder = offer(auction, 1).get("order_id");
        expire(auction, 1);

        job.expireOffers();

        assertThat(offer(auction, 1).get("status")).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, firstOrder)).isEqualTo("CANCELLED");

        Map<String, Object> second = offer(auction, 2);
        assertThat(second.get("status")).isEqualTo("PENDING");
        assertThat(second.get("user_id")).isEqualTo(ben.getId());
        Map<String, Object> order = jdbc.queryForMap("select source, status, total_amount, user_id, expires_at from orders where id = ?", second.get("order_id"));
        assertThat(order.get("source")).isEqualTo("AUCTION");
        assertThat(order.get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(order.get("total_amount")).as("Ben pays his own highest bid, not Ann's").isEqualTo(450_000L);
        assertThat(order.get("user_id")).isEqualTo(ben.getId());
        assertThat(second.get("expires_at")).as("the offer and its order share one deadline").isEqualTo(order.get("expires_at"));

        assertThat(statusOf(auction)).as("still waiting for payment").isEqualTo("AWAITING_PAYMENT");
        assertThat(seatStatus(seat)).as("the seat never went back on sale").isEqualTo("RESERVED_FOR_AUCTION");
        assertThat(pendingOffers(auction)).as("only one offer is open at a time").isEqualTo(1);
    }

    @Test
    void aBidderWhoBidSeveralTimes_countsOnce_atTheirHighestBid() {
        Fixture f = fixture(10);
        User ann = bidder();
        User ben = bidder();
        UUID auction = awaitingPayment(standing(f, null),
                new Bidding(ann, 500_000), new Bidding(ann, 400_000), new Bidding(ben, 300_000));
        expire(auction, 1);

        job.expireOffers();

        assertThat(offer(auction, 2).get("user_id")).as("Ann already had her offer: the next one is Ben").isEqualTo(ben.getId());
        assertThat(offerCount(auction)).isEqualTo(2);
    }

    @Test
    void whenTheSecondOfferExpiresToo_itPassesToTheThirdBidder() {
        Fixture f = fixture(10);
        User ann = bidder();
        User ben = bidder();
        User cat = bidder();
        UUID auction = awaitingPayment(standing(f, null),
                new Bidding(ann, 500_000), new Bidding(ben, 450_000), new Bidding(cat, 400_000));
        expire(auction, 1);
        job.expireOffers();
        expire(auction, 2);

        job.expireOffers();

        assertThat(offer(auction, 2).get("status")).isEqualTo("EXPIRED");
        assertThat(offer(auction, 3).get("user_id")).isEqualTo(cat.getId());
        assertThat(offer(auction, 3).get("status")).isEqualTo("PENDING");
        assertThat(statusOf(auction)).isEqualTo("AWAITING_PAYMENT");
    }

    // ---- nobody is left --------------------------------------------------------------------------

    @Test
    void whenNobodyIsLeft_aSeatedAuctionFails_andTheSeatGoesBackOnSale() {
        Fixture f = fixture(10);
        UUID seat = f.seatIds().get(0);
        UUID auction = awaitingPayment(seated(f, seat, null), new Bidding(bidder(), 500_000));
        expire(auction, 1);

        job.expireOffers();

        assertThat(offer(auction, 1).get("status")).isEqualTo("EXPIRED");
        assertThat(statusOf(auction)).isEqualTo("FAILED");
        assertThat(seatStatus(seat)).isEqualTo("AVAILABLE");
        assertThat(offerCount(auction)).as("no offer for anybody else").isEqualTo(1);
    }

    @Test
    void whenNobodyIsLeft_aStandingAuctionFails_andTheTicketGoesBackToTheCounter() {
        Fixture f = fixture(10);
        UUID auction = awaitingPayment(standing(f, null), new Bidding(bidder(), 500_000));
        assertThat(available(f)).isEqualTo(9);
        expire(auction, 1);

        job.expireOffers();

        assertThat(statusOf(auction)).isEqualTo("FAILED");
        assertThat(available(f)).isEqualTo(10);
    }

    @Test
    void aNextBidUnderTheReserve_isNotOffered_andTheAuctionFails() {
        Fixture f = fixture(10);
        UUID auction = awaitingPayment(standing(f, 400_000L),
                new Bidding(bidder(), 500_000), new Bidding(bidder(), 350_000));
        expire(auction, 1);

        job.expireOffers();

        assertThat(statusOf(auction)).isEqualTo("FAILED");
        assertThat(offerCount(auction)).isEqualTo(1);
        assertThat(available(f)).isEqualTo(10);
    }

    @Test
    void aTicketThatCameBack_clearsTheGateFlags() {
        Fixture f = fixture(10);
        UUID seat = f.seatIds().get(0);
        UUID seatedAuction = awaitingPayment(seated(f, seat, null), new Bidding(bidder(), 500_000));
        UUID standingAuction = awaitingPayment(standing(f, null), new Bidding(bidder(), 500_000));
        expire(seatedAuction, 1);
        expire(standingAuction, 1);
        gateway.setSeatFlagged(f.seatedZoneId(), List.of(seat));
        gateway.setQuantityRejected(f.standingZoneId(), 1);

        job.expireOffers();

        assertThat(gateway.flaggedSeats(f.seatedZoneId(), List.of(seat))).isEmpty();
        assertThat(gateway.mayTakeQuantity(f.standingZoneId(), 1)).isTrue();
    }

    // ---- the order was paid at the last moment ---------------------------------------------------

    @Test
    void anOfferWhoseOrderWasPaid_isAccepted_andNothingPassesOn() {
        Fixture f = fixture(10);
        User ann = bidder();
        UUID auction = awaitingPayment(standing(f, null), new Bidding(ann, 500_000), new Bidding(bidder(), 450_000));
        Object order = offer(auction, 1).get("order_id");
        jdbc.update("update orders set status = 'PAID', paid_at = now() where id = ?", order);
        expire(auction, 1);

        job.expireOffers();

        assertThat(offer(auction, 1).get("status")).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, order)).as("the payment stands").isEqualTo("PAID");
        assertThat(statusOf(auction)).isEqualTo("SETTLED");
        assertThat(offerCount(auction)).as("nobody else gets an offer").isEqualTo(1);
        assertThat(available(f)).as("the ticket is sold, not back on the counter").isEqualTo(9);
    }

    // ---- when it is not time, or not ours --------------------------------------------------------

    @Test
    void anOfferStillInItsPaymentWindow_isLeftAlone() {
        Fixture f = fixture(10);
        UUID auction = awaitingPayment(standing(f, null), new Bidding(bidder(), 500_000), new Bidding(bidder(), 450_000));

        job.expireOffers();

        assertThat(offer(auction, 1).get("status")).isEqualTo("PENDING");
        assertThat(offerCount(auction)).isEqualTo(1);
        assertThat(statusOf(auction)).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void severalJobsAtOnce_passEachOfferOnExactlyOnce() throws Exception {
        int auctions = 30;   // enough that the jobs really overlap
        Fixture f = fixture(auctions);
        User ann = bidder();
        User ben = bidder();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < auctions; i++) {
            UUID auction = awaitingPayment(standing(f, null), new Bidding(ann, 500_000), new Bidding(ben, 450_000));
            expire(auction, 1);
            ids.add(auction);
        }

        int jobs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < jobs; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                job.expireOffers();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> run : runs) {
            run.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        for (UUID id : ids) {
            assertThat(offerCount(id)).as("Ann's offer and Ben's, never a third").isEqualTo(2);
            assertThat(pendingOffers(id)).isEqualTo(1);
            assertThat(offer(id, 2).get("user_id")).isEqualTo(ben.getId());
        }
        assertThat(jdbc.queryForObject("select count(*) from orders o join events e on e.id = o.event_id where e.public_id = ? "
                + "and o.status = 'PENDING_PAYMENT'", Integer.class, f.eventId())).as("one unpaid order per auction").isEqualTo(auctions);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Fixture fixture(int standingCapacity) {
        return AuctionFixtures.create(users, venues, events, zones, jdbc, standingCapacity, true);
    }

    private User bidder() {
        return users.save(new User("bidder-" + UUID.randomUUID() + "@test.dev", "hash", "Bidder", UserRole.CUSTOMER));
    }

    private UUID seated(Fixture f, UUID seat, Long reserve) {
        return auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), f.seatedZoneId(), seat, 100_000L, 10_000L, reserve,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), null, null)).publicId();
    }

    private UUID standing(Fixture f, Long reserve) {
        return auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), f.standingZoneId(), null, 100_000L, 10_000L, reserve,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), null, null)).publicId();
    }

    /**
     * Ends the auction with these bids (the price and leader are what the bid step would have written) and runs the
     * real closing job, so it is AWAITING_PAYMENT with a rank-1 offer for the highest bidder.
     */
    private UUID awaitingPayment(UUID auction, Bidding... biddings) {
        Bidding top = biddings[0];
        for (Bidding b : biddings) {
            jdbc.update("insert into bids (auction_id, bidder_id, amount) select id, ?, ? from auctions where public_id = ?",
                    b.by().getId(), b.amount(), auction);
            if (b.amount() > top.amount()) {
                top = b;
            }
        }
        jdbc.update("update auctions set status = 'LIVE', starts_at = now() - interval '2 hours', "
                        + "ends_at = now() - interval '1 minute', current_price = ?, leading_bidder_id = ?, bid_count = ? "
                        + "where public_id = ?", top.amount(), top.by().getId(), biddings.length, auction);
        closingJob.closeAuction();
        assertThat(statusOf(auction)).as("set-up: the closing job awarded it").isEqualTo("AWAITING_PAYMENT");
        return auction;
    }

    /** The offer of this rank is past its deadline. */
    private void expire(UUID auction, int rank) {
        jdbc.update("update auction_offers set expires_at = now() - interval '1 minute' where offer_rank = ? "
                + "and auction_id = (select id from auctions where public_id = ?)", rank, auction);
    }

    private Map<String, Object> offer(UUID auction, int rank) {
        return jdbc.queryForMap("select ao.status, ao.user_id, ao.order_id, ao.expires_at from auction_offers ao "
                + "join auctions a on a.id = ao.auction_id where a.public_id = ? and ao.offer_rank = ?", auction, rank);
    }

    private int offerCount(UUID auction) {
        return jdbc.queryForObject("select count(*) from auction_offers ao join auctions a on a.id = ao.auction_id "
                + "where a.public_id = ?", Integer.class, auction);
    }

    private int pendingOffers(UUID auction) {
        return jdbc.queryForObject("select count(*) from auction_offers ao join auctions a on a.id = ao.auction_id "
                + "where a.public_id = ? and ao.status = 'PENDING'", Integer.class, auction);
    }

    private String statusOf(UUID auction) {
        return jdbc.queryForObject("select status from auctions where public_id = ?", String.class, auction);
    }

    private int available(Fixture f) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where id = ?", Integer.class, f.standingZoneDbId());
    }

    private String seatStatus(UUID seat) {
        return jdbc.queryForObject("select status from seats where public_id = ?", String.class, seat);
    }
}
