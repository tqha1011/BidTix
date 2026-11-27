package com.bidtix.auction.service;

import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.infrastructure.AuctionClosingJob;
import com.bidtix.auction.infrastructure.CancelledEventAuctionJob;
import com.bidtix.auction.service.AuctionFixtures.Fixture;
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
 * The job that calls off the open auctions of a cancelled event. The scheduled run of the same job also works on the
 * shared database, so every assertion is about a state that stays true whoever got there. Auctions awaiting payment are
 * produced by the real closing job.
 */
class CancelledEventAuctionJobTest extends PostgresIntegrationTest {

    @Autowired CancelledEventAuctionJob job;
    @Autowired AuctionClosingJob closingJob;
    @Autowired AuctionService auctionService;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;
    @Autowired PayableOrders payableOrders;

    private final Instant now = Instant.now();

    @Test
    void aScheduledAuction_isCancelled_andItsTicketStaysOutOfSale() {
        Fixture f = fixture(10);
        UUID auction = standing(f);
        cancelEvent(f, 0);

        job.cancelAuctionsOfCancelledEvents();

        assertThat(statusOf(auction)).isEqualTo("CANCELLED");
        assertThat(available(f)).as("nobody can buy for a cancelled event: nothing goes back on sale").isEqualTo(9);
    }

    @Test
    void aLiveAuctionWithBids_isCancelled() {
        Fixture f = fixture(10);
        UUID auction = seated(f, f.seatIds().get(0));
        bid(auction, bidder(), 120_000);
        jdbc.update("update auctions set status = 'LIVE', starts_at = now() - interval '1 hour', current_price = 120000, "
                + "leading_bidder_id = (select bidder_id from bids b where b.auction_id = auctions.id), bid_count = 1 "
                + "where public_id = ?", auction);
        cancelEvent(f, 0);

        job.cancelAuctionsOfCancelledEvents();

        assertThat(statusOf(auction)).isEqualTo("CANCELLED");
    }

    @Test
    void anAuctionAwaitingPayment_isCancelled_withItsOffer_andItsUnpaidOrder() {
        Fixture f = fixture(10);
        UUID auction = awaitingPayment(standing(f));
        cancelEvent(f, 0);

        job.cancelAuctionsOfCancelledEvents();

        assertThat(statusOf(auction)).isEqualTo("CANCELLED");
        assertThat(offerStatus(auction)).isEqualTo("EXPIRED");
        assertThat(orderStatus(auction)).isEqualTo("CANCELLED");
        assertThat(available(f)).isEqualTo(9);
    }

    @Test
    void aSettledAuction_isLeftAlone() {
        Fixture f = fixture(10);
        UUID auction = awaitingPayment(standing(f));
        long orderId = orderId(auction);
        payableOrders.applyPayment(orderId, Instant.now());
        // the booking module refunds the paid order: give it a payment to refund
        jdbc.update("insert into payments (order_id, provider, txn_ref, amount, status, requested_at) "
                        + "select id, 'VNPAY', ?, total_amount, 'SUCCEEDED', now() from orders where id = ?",
                UUID.randomUUID().toString().replace("-", ""), orderId);
        cancelEvent(f, 0);

        job.cancelAuctionsOfCancelledEvents();

        assertThat(statusOf(auction)).isEqualTo("SETTLED");
        assertThat(offerStatus(auction)).isEqualTo("ACCEPTED");
    }

    @Test
    void auctionsOfAnEventThatIsNotCancelled_areLeftAlone() {
        Fixture f = fixture(10);
        UUID scheduled = standing(f);
        UUID awaiting = awaitingPayment(standing(f));

        job.cancelAuctionsOfCancelledEvents();

        assertThat(statusOf(scheduled)).isEqualTo("SCHEDULED");
        assertThat(statusOf(awaiting)).isEqualTo("AWAITING_PAYMENT");
        assertThat(offerStatus(awaiting)).isEqualTo("PENDING");
        assertThat(orderStatus(awaiting)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void moreOpenAuctionsThanOneBatch_areAllCancelled() {
        int auctions = 105;   // the job takes 100 at a time
        Fixture f = fixture(auctions);
        for (int i = 0; i < auctions; i++) {
            standing(f);
        }
        cancelEvent(f, 0);

        job.cancelAuctionsOfCancelledEvents();

        assertThat(jdbc.queryForObject("select count(*) from auctions a join events e on e.id = a.event_id "
                + "where e.public_id = ? and a.status = 'CANCELLED'", Integer.class, f.eventId())).isEqualTo(auctions);
    }

    // ---- remembering that an event is done -------------------------------------------------

    @Test
    void anEventWithNoOpenAuctionLeft_isMarkedCleaned_onceTheSettleWindowIsOver() {
        Fixture f = fixture(10);
        standing(f);
        awaitingPayment(standing(f));
        cancelEvent(f, 6);

        job.cancelAuctionsOfCancelledEvents();

        assertThat(markedCleaned(f)).isTrue();
        assertThat(jdbc.queryForObject("select orders_cleaned_at from events where public_id = ?", Instant.class,
                f.eventId())).as("that mark belongs to the booking module").isNull();
    }

    @Test
    void anEventWithoutAnyAuction_isMarkedCleanedToo() {
        Fixture f = fixture(10);
        cancelEvent(f, 6);

        job.cancelAuctionsOfCancelledEvents();

        assertThat(markedCleaned(f)).isTrue();
    }

    @Test
    void anEventCancelledMomentsAgo_isNotMarkedYet_soALateAuctionIsStillFound() {
        Fixture f = fixture(10);
        cancelEvent(f, 4);

        job.cancelAuctionsOfCancelledEvents();

        assertThat(markedCleaned(f)).isFalse();
    }

    @Test
    void anEventMarkedCleaned_isNotLookedAtAgain() {
        Fixture f = fixture(10);
        UUID auction = standing(f);
        cancelEvent(f, 6);
        jdbc.update("update events set auctions_cleaned_at = now() where public_id = ?", f.eventId());

        job.cancelAuctionsOfCancelledEvents();

        assertThat(statusOf(auction)).isEqualTo("SCHEDULED");
    }

    @Test
    void anAuctionThatCannotBeCancelled_staysWhole_keepsItsEventUnmarked_andDoesNotStopTheOthers() {
        Fixture f = fixture(10);
        UUID broken = awaitingPayment(standing(f));
        UUID good = awaitingPayment(standing(f));
        // its order is not unpaid any more although its offer is still open: something a person has to look at
        jdbc.update("update orders set status = 'CANCELLED' where id = ?", orderId(broken));
        cancelEvent(f, 6);

        job.cancelAuctionsOfCancelledEvents();

        assertThat(statusOf(good)).isEqualTo("CANCELLED");
        assertThat(statusOf(broken)).as("rolled back, retried on the next run").isEqualTo("AWAITING_PAYMENT");
        assertThat(offerStatus(broken)).isEqualTo("PENDING");
        assertThat(markedCleaned(f)).isFalse();
        // otherwise the scheduled job keeps failing on it for as long as the tests run
        jdbc.update("update auction_offers set status = 'EXPIRED' where order_id = ?", orderId(broken));
        jdbc.update("update auctions set status = 'CANCELLED' where public_id = ?", broken);
    }

    @Test
    void severalJobsAtOnce_cancelEachAuctionExactlyOnce() throws Exception {
        Fixture f = fixture(20);
        List<UUID> auctions = new ArrayList<>();
        List<Long> versionsBefore = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID auction = awaitingPayment(standing(f));
            auctions.add(auction);
            versionsBefore.add(auctionVersion(auction));
        }
        cancelEvent(f, 0);

        int jobs = 4;
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < jobs; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                job.cancelAuctionsOfCancelledEvents();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> run : runs) {
            run.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        for (int i = 0; i < auctions.size(); i++) {
            UUID auction = auctions.get(i);
            assertThat(statusOf(auction)).isEqualTo("CANCELLED");
            assertThat(offerStatus(auction)).isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject("select version from orders where id = ?", Long.class, orderId(auction)))
                    .as("the order was cancelled once").isEqualTo(1);
            assertThat(auctionVersion(auction)).isEqualTo(versionsBefore.get(i) + 1);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Fixture fixture(int standingCapacity) {
        return AuctionFixtures.create(users, venues, events, zones, jdbc, standingCapacity, true);
    }

    private User bidder() {
        return users.save(new User("bidder-" + UUID.randomUUID() + "@test.dev", "hash", "Bidder", UserRole.CUSTOMER));
    }

    private UUID seated(Fixture f, UUID seat) {
        return create(f, f.seatedZoneId(), seat);
    }

    private UUID standing(Fixture f) {
        return create(f, f.standingZoneId(), null);
    }

    private UUID create(Fixture f, UUID zone, UUID seat) {
        return auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), zone, seat, 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), null, null)).publicId();
    }

    private void bid(UUID auction, User by, long amount) {
        jdbc.update("insert into bids (auction_id, bidder_id, amount) select id, ?, ? from auctions where public_id = ?",
                by.getId(), amount, auction);
    }

    /** Ends the auction with one bid and runs the real closing job: AWAITING_PAYMENT with an offer and an unpaid order. */
    private UUID awaitingPayment(UUID auction) {
        User winner = bidder();
        bid(auction, winner, 120_000);
        jdbc.update("update auctions set status = 'LIVE', starts_at = now() - interval '2 hours', "
                + "ends_at = now() - interval '1 minute', current_price = 120000, leading_bidder_id = ?, bid_count = 1 "
                + "where public_id = ?", winner.getId(), auction);
        closingJob.closeAuction();
        assertThat(statusOf(auction)).as("set-up: the closing job awarded it").isEqualTo("AWAITING_PAYMENT");
        return auction;
    }

    private void cancelEvent(Fixture f, int minutesAgo) {
        jdbc.update("update events set status = 'CANCELLED', cancelled_at = now() - make_interval(mins => ?) "
                + "where public_id = ?", minutesAgo, f.eventId());
    }

    private boolean markedCleaned(Fixture f) {
        return jdbc.queryForObject("select auctions_cleaned_at is not null from events where public_id = ?",
                Boolean.class, f.eventId());
    }

    private String statusOf(UUID auction) {
        return jdbc.queryForObject("select status from auctions where public_id = ?", String.class, auction);
    }

    private long auctionVersion(UUID auction) {
        return jdbc.queryForObject("select version from auctions where public_id = ?", Long.class, auction);
    }

    private String offerStatus(UUID auction) {
        return jdbc.queryForObject("select ao.status from auction_offers ao join auctions a on a.id = ao.auction_id "
                + "where a.public_id = ?", String.class, auction);
    }

    private long orderId(UUID auction) {
        return jdbc.queryForObject("select ao.order_id from auction_offers ao join auctions a on a.id = ao.auction_id "
                + "where a.public_id = ?", Long.class, auction);
    }

    private String orderStatus(UUID auction) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId(auction));
    }

    private int available(Fixture f) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where id = ?", Integer.class,
                f.standingZoneDbId());
    }
}
