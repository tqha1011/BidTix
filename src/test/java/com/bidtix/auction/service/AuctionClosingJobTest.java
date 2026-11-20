package com.bidtix.auction.service;

import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.infrastructure.AuctionClosingJob;
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
 * The job that closes auctions whose time is over: a winner gets an AUCTION order and a rank-1 offer
 * (AWAITING_PAYMENT); no bid, or a final price under the reserve, means FAILED and the ticket goes back on sale.
 * The application runs the same job every minute, so these tests only look at their own auctions and never at
 * what the job returns.
 */
class AuctionClosingJobTest extends PostgresIntegrationTest {

    @Autowired AuctionClosingJob job;
    @Autowired AuctionService auctionService;
    @Autowired BookingGateway gateway;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    // ---- a winner --------------------------------------------------------------------------------

    @Test
    void aSeatedAuctionWithAWinner_awaitsPayment_withAnOrderAndARankOneOffer() {
        Fixture f = fixture(10);
        UUID seat = f.seatIds().get(0);
        UUID auction = seated(f, seat, null);
        User bidder = bidder();
        endedWithBid(auction, bidder, 150_000);

        job.closeAuction();

        assertThat(statusOf(auction)).isEqualTo("AWAITING_PAYMENT");
        Map<String, Object> order = orderOf(auction);
        assertThat(order.get("source")).isEqualTo("AUCTION");
        assertThat(order.get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(order.get("user_id")).isEqualTo(bidder.getId());
        assertThat(order.get("total_amount")).isEqualTo(150_000L);
        Map<String, Object> item = jdbc.queryForMap("select seat_id, quantity, unit_price from order_items where order_id = ?", order.get("id"));
        assertThat(item.get("quantity")).isEqualTo(1);
        assertThat(item.get("unit_price")).isEqualTo(150_000L);
        assertThat(item.get("seat_id")).isEqualTo(seatDbId(seat));

        Map<String, Object> offer = offerOf(auction);
        assertThat(offer.get("offer_rank")).isEqualTo(1);
        assertThat(offer.get("status")).isEqualTo("PENDING");
        assertThat(offer.get("user_id")).isEqualTo(bidder.getId());
        assertThat(offer.get("order_id")).isEqualTo(order.get("id"));
        assertThat(offer.get("expires_at")).as("the offer and the order share one deadline").isEqualTo(order.get("expires_at"));
        assertThat(jdbc.queryForObject("select amount from bids where id = ?", Long.class, offer.get("bid_id"))).isEqualTo(150_000L);

        assertThat(seatStatus(seat)).as("the winner has not paid yet: the seat stays out of sale").isEqualTo("RESERVED_FOR_AUCTION");
    }

    @Test
    void aStandingAuctionWithAWinner_awaitsPayment_andTheCounterIsNotTouched() {
        Fixture f = fixture(10);
        UUID auction = standing(f, null);
        endedWithBid(auction, bidder(), 120_000);
        int before = available(f);

        job.closeAuction();

        assertThat(statusOf(auction)).isEqualTo("AWAITING_PAYMENT");
        Map<String, Object> item = jdbc.queryForMap("select seat_id, quantity, unit_price from order_items where order_id = ?", orderOf(auction).get("id"));
        assertThat(item.get("seat_id")).isNull();
        assertThat(item.get("quantity")).isEqualTo(1);
        assertThat(available(f)).isEqualTo(before);
    }

    @Test
    void aFinalPriceAtTheReserve_isEnough() {
        Fixture f = fixture(10);
        UUID auction = standing(f, 150_000L);
        endedWithBid(auction, bidder(), 150_000);

        job.closeAuction();

        assertThat(statusOf(auction)).isEqualTo("AWAITING_PAYMENT");
    }

    // ---- no winner -------------------------------------------------------------------------------

    @Test
    void aSeatedAuctionWithoutBids_fails_andTheSeatGoesBackOnSale() {
        Fixture f = fixture(10);
        UUID seat = f.seatIds().get(0);
        UUID auction = seated(f, seat, null);
        ended(auction);

        job.closeAuction();

        assertThat(statusOf(auction)).isEqualTo("FAILED");
        assertThat(seatStatus(seat)).isEqualTo("AVAILABLE");
        assertThat(ordersOf(f)).isZero();
    }

    @Test
    void aStandingAuctionWithoutBids_fails_andTheTicketGoesBackToTheCounter() {
        Fixture f = fixture(10);
        UUID auction = standing(f, null);
        ended(auction);
        assertThat(available(f)).isEqualTo(9);

        job.closeAuction();

        assertThat(statusOf(auction)).isEqualTo("FAILED");
        assertThat(available(f)).isEqualTo(10);
        assertThat(ordersOf(f)).isZero();
    }

    @Test
    void aFinalPriceUnderTheReserve_fails_andTheTicketGoesBack() {
        Fixture f = fixture(10);
        UUID auction = standing(f, 500_000L);
        endedWithBid(auction, bidder(), 150_000);

        job.closeAuction();

        assertThat(statusOf(auction)).isEqualTo("FAILED");
        assertThat(available(f)).isEqualTo(10);
        assertThat(ordersOf(f)).isZero();
    }

    // ---- the booking gate ------------------------------------------------------------------------

    @Test
    void tickets_thatCameBack_clearTheGateFlags() {
        Fixture f = fixture(10);
        UUID seat = f.seatIds().get(0);
        UUID seatedAuction = seated(f, seat, null);
        UUID standingAuction = standing(f, null);
        ended(seatedAuction);
        ended(standingAuction);
        gateway.setSeatFlagged(f.seatedZoneId(), List.of(seat));
        gateway.setQuantityRejected(f.standingZoneId(), 1);

        job.closeAuction();

        assertThat(gateway.flaggedSeats(f.seatedZoneId(), List.of(seat))).isEmpty();
        assertThat(gateway.mayTakeQuantity(f.standingZoneId(), 1)).isTrue();
    }

    // ---- when it is not time, or not ours --------------------------------------------------------

    @Test
    void anAuctionThatHasNotEnded_isLeftAlone() {
        Fixture f = fixture(10);
        UUID auction = standing(f, null);
        jdbc.update("update auctions set status = 'LIVE' where public_id = ?", auction);

        job.closeAuction();

        assertThat(statusOf(auction)).isEqualTo("LIVE");
        assertThat(available(f)).isEqualTo(9);
    }

    @Test
    void anAuctionThatWasNeverOpened_isLeftAlone() {
        Fixture f = fixture(10);
        UUID auction = standing(f, null);   // SCHEDULED

        job.closeAuction();

        assertThat(statusOf(auction)).isEqualTo("SCHEDULED");
    }

    @Test
    void manyEndedAuctions_areAllClosedInOneRun() {
        int auctions = 5;
        Fixture f = fixture(auctions);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < auctions; i++) {
            UUID auction = standing(f, null);
            ended(auction);
            ids.add(auction);
        }

        job.closeAuction();

        assertThat(ids).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("FAILED"));
        assertThat(available(f)).isEqualTo(auctions);
    }

    @Test
    void anAuctionThatCannotBeClosed_doesNotStopTheOthers() {
        Fixture f = fixture(10);
        UUID broken = standing(f, null);
        UUID good = standing(f, null);
        endedWithBid(good, bidder(), 150_000);
        // a leading bidder and price, but no bid row behind them: the data is inconsistent and cannot be awarded
        jdbc.update("update auctions set status = 'LIVE', starts_at = now() - interval '2 hours', "
                + "ends_at = now() - interval '1 minute', current_price = 140000, bid_count = 1, "
                + "leading_bidder_id = ? where public_id = ?", bidder().getId(), broken);

        job.closeAuction();

        assertThat(statusOf(good)).isEqualTo("AWAITING_PAYMENT");
        assertThat(statusOf(broken)).as("rolled back, retried on the next run").isEqualTo("LIVE");
    }

    @Test
    void severalJobsAtOnce_closeEachAuctionExactlyOnce() throws Exception {
        int auctions = 40;   // enough that the jobs really overlap
        Fixture f = fixture(auctions);
        User bidder = bidder();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < auctions; i++) {
            UUID auction = standing(f, null);
            endedWithBid(auction, bidder, 150_000);
            ids.add(auction);
        }

        int jobs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < jobs; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                job.closeAuction();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> run : runs) {
            run.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        for (UUID id : ids) {
            assertThat(statusOf(id)).isEqualTo("AWAITING_PAYMENT");
            assertThat(jdbc.queryForObject("select count(*) from auction_offers ao join auctions a on a.id = ao.auction_id "
                    + "where a.public_id = ?", Integer.class, id)).as("one offer").isEqualTo(1);
        }
        assertThat(ordersOf(f)).as("one order per auction, never two").isEqualTo(auctions);
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

    /** LIVE and past its end time, with no bids. */
    private void ended(UUID auction) {
        jdbc.update("update auctions set status = 'LIVE', starts_at = now() - interval '2 hours', "
                + "ends_at = now() - interval '1 minute' where public_id = ?", auction);
    }

    /** Ended, with one accepted bid from {@code bidder}: the price and the leader are what the bid step would have written. */
    private void endedWithBid(UUID auction, User bidder, long amount) {
        ended(auction);
        jdbc.update("insert into bids (auction_id, bidder_id, amount) select id, ?, ? from auctions where public_id = ?",
                bidder.getId(), amount, auction);
        jdbc.update("update auctions set current_price = ?, leading_bidder_id = ?, bid_count = 1 where public_id = ?",
                amount, bidder.getId(), auction);
    }

    private String statusOf(UUID auction) {
        return jdbc.queryForObject("select status from auctions where public_id = ?", String.class, auction);
    }

    private Map<String, Object> orderOf(UUID auction) {
        return jdbc.queryForMap("select o.id, o.source, o.status, o.user_id, o.total_amount, o.expires_at from orders o "
                + "join auction_offers ao on ao.order_id = o.id join auctions a on a.id = ao.auction_id where a.public_id = ?", auction);
    }

    private Map<String, Object> offerOf(UUID auction) {
        return jdbc.queryForMap("select ao.offer_rank, ao.status, ao.user_id, ao.order_id, ao.bid_id, ao.expires_at "
                + "from auction_offers ao join auctions a on a.id = ao.auction_id where a.public_id = ?", auction);
    }

    private int ordersOf(Fixture f) {
        return jdbc.queryForObject("select count(*) from orders o join events e on e.id = o.event_id where e.public_id = ?",
                Integer.class, f.eventId());
    }

    private int available(Fixture f) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where id = ?", Integer.class, f.standingZoneDbId());
    }

    private String seatStatus(UUID seat) {
        return jdbc.queryForObject("select status from seats where public_id = ?", String.class, seat);
    }

    private long seatDbId(UUID seat) {
        return jdbc.queryForObject("select id from seats where public_id = ?", Long.class, seat);
    }
}
