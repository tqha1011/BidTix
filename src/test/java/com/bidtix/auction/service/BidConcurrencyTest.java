package com.bidtix.auction.service;

import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.dtos.PlaceBidRequest;
import com.bidtix.auction.service.AuctionFixtures.Fixture;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many bidders at the same instant on one auction (start price 100_000, minimum increment 10_000): no bid is lost or
 * overwritten, the final price is the highest accepted bid, and the anti-sniping extension is applied once.
 */
class BidConcurrencyTest extends PostgresIntegrationTest {

    private static final int BIDDERS = 12;
    private static final long START = 100_000L;
    private static final long INCREMENT = 10_000L;

    @Autowired BidService bidService;
    @Autowired AuctionService auctionService;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    @Test
    void manyDifferentBids_theHighestAlwaysWins_andNothingIsLost() throws Exception {
        UUID auction = liveAuction(3600);
        List<User> bidders = bidders(BIDDERS);
        // spaced by the increment, so the highest bid is valid whatever the others did before it
        List<Long> amounts = new ArrayList<>();
        for (int i = 0; i < BIDDERS; i++) {
            amounts.add(START + i * INCREMENT);
        }

        List<String> outcomes = race(bidders, amounts, auction);

        long highest = amounts.get(BIDDERS - 1);
        assertThat(outcomes.stream().filter(o -> !o.equals("ACCEPTED")))
                .as("a refused bid is refused for its price only").containsOnly(ErrorCode.INVALID_MONEY_BID.name());
        long accepted = outcomes.stream().filter("ACCEPTED"::equals).count();
        assertThat(accepted).isPositive();

        List<Map<String, Object>> bids = jdbc.queryForList("select b.bidder_id, b.amount from bids b "
                + "join auctions a on a.id = b.auction_id where a.public_id = ? order by b.id", auction);
        assertThat(bids).as("one stored bid per accepted bid").hasSize((int) accepted);
        assertThat(jdbc.queryForObject("select bid_count from auctions where public_id = ?", Integer.class, auction))
                .as("the counter matches the stored bids").isEqualTo((int) accepted);

        long previous = 0;
        for (Map<String, Object> bid : bids) {
            long amount = (Long) bid.get("amount");
            long floor = previous == 0 ? START : previous + INCREMENT;
            assertThat(amount).as("every accepted bid beat the one before it by the increment").isGreaterThanOrEqualTo(floor);
            previous = amount;
        }
        assertThat(previous).as("final price = the highest accepted bid = the highest bid sent").isEqualTo(highest);
        Map<String, Object> auctionRow = jdbc.queryForMap("select current_price, leading_bidder_id from auctions where public_id = ?", auction);
        assertThat(auctionRow.get("current_price")).isEqualTo(highest);
        assertThat(auctionRow.get("leading_bidder_id")).isEqualTo(bidders.get(BIDDERS - 1).getId());
    }

    @Test
    void manyBidsWithTheSameAmount_exactlyOneIsAccepted() throws Exception {
        UUID auction = liveAuction(3600);
        List<User> bidders = bidders(BIDDERS);
        List<Long> amounts = new ArrayList<>();
        for (int i = 0; i < BIDDERS; i++) {
            amounts.add(150_000L);
        }

        List<String> outcomes = race(bidders, amounts, auction);

        assertThat(outcomes.stream().filter("ACCEPTED"::equals)).as("exactly one wins").hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.equals("ACCEPTED"))).hasSize(BIDDERS - 1)
                .containsOnly(ErrorCode.INVALID_MONEY_BID.name());
        assertThat(jdbc.queryForObject("select current_price from auctions where public_id = ?", Long.class, auction)).isEqualTo(150_000L);
        assertThat(jdbc.queryForObject("select bid_count from auctions where public_id = ?", Integer.class, auction)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from bids b join auctions a on a.id = b.auction_id where a.public_id = ?",
                Integer.class, auction)).isEqualTo(1);
    }

    @Test
    void manyBidsInsideTheAntiSnipingWindow_extendTheEndOnce() throws Exception {
        UUID auction = liveAuction(30); // inside the 60 s window; one extension of 60 s moves the end out of it
        Instant before = endsAt(auction);
        List<User> bidders = bidders(BIDDERS);
        List<Long> amounts = new ArrayList<>();
        for (int i = 0; i < BIDDERS; i++) {
            amounts.add(START + i * INCREMENT);
        }

        List<String> outcomes = race(bidders, amounts, auction);

        assertThat(outcomes.stream().filter("ACCEPTED"::equals)).isNotEmpty();
        assertThat(endsAt(auction)).as("the first bid extends; the others see an end that is already out of the window")
                .isEqualTo(before.plusSeconds(60));
    }

    @Test
    void bidsAtTheMomentTheAuctionEnds_areNeverAcceptedAfterIt() throws Exception {
        UUID auction = liveAuction(-1);
        List<User> bidders = bidders(BIDDERS);
        List<Long> amounts = new ArrayList<>();
        for (int i = 0; i < BIDDERS; i++) {
            amounts.add(START + i * INCREMENT);
        }

        List<String> outcomes = race(bidders, amounts, auction);

        assertThat(outcomes).containsOnly(ErrorCode.BID_NO_LONGER_AVAILABLE.name());
        assertThat(jdbc.queryForObject("select count(*) from bids b join auctions a on a.id = b.auction_id where a.public_id = ?",
                Integer.class, auction)).isZero();
    }

    /** Releases one bid per bidder at the same instant; each result is "ACCEPTED" or the error code. */
    private List<String> race(List<User> bidders, List<Long> amounts, UUID auction) throws Exception {
        int n = bidders.size();
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                UUID bidder = bidders.get(i).getPublicId();
                long amount = amounts.get(i);
                Callable<String> call = () -> {
                    ready.countDown();
                    go.await();
                    try {
                        bidService.placeBid(PlaceBidRequest.create(auction, amount), bidder);
                        return "ACCEPTED";
                    } catch (BusinessException e) {
                        return e.getErrorCode().name();
                    }
                };
                results.add(pool.submit(call));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<User> bidders(int count) {
        List<User> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(users.save(new User("bidder-" + UUID.randomUUID() + "@test.dev", "hash", "Bidder", UserRole.CUSTOMER)));
        }
        return list;
    }

    /** A LIVE auction (anti-sniping window 60 s, extension 60 s) with {@code secondsLeft} until it ends. */
    private UUID liveAuction(int secondsLeft) {
        Fixture f = AuctionFixtures.create(users, venues, events, zones, jdbc, 10, true);
        UUID auction = auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), f.standingZoneId(), null, START, INCREMENT, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), 60, 60)).publicId();
        jdbc.update("update auctions set status = 'LIVE', starts_at = now() - interval '2 hours', "
                + "ends_at = now() + interval '1 second' * ? where public_id = ?", secondsLeft, auction);
        return auction;
    }

    private Instant endsAt(UUID auction) {
        return jdbc.queryForObject("select ends_at from auctions where public_id = ?", java.sql.Timestamp.class, auction).toInstant();
    }
}
