package com.bidtix.auction.service;

import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.domain.LeaderboardEntry;
import com.bidtix.auction.dtos.LeaderboardEntryResponse;
import com.bidtix.auction.infrastructure.AuctionLeaderBoard;
import com.bidtix.auction.infrastructure.AuctionRepository;
import com.bidtix.auction.infrastructure.BidRepository;
import com.bidtix.auction.infrastructure.LeaderboardProperties;
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
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code LeaderboardService}: Redis first, the database when Redis has nothing (or is down), names from the user table.
 * Bids are written straight into the table (with the auction's {@code bid_count}, which is what decides a rebuild).
 */
class LeaderboardServiceTest extends PostgresIntegrationTest {

    @Autowired LeaderboardService service;
    @Autowired AuctionLeaderBoard board;
    @Autowired StringRedisTemplate redis;
    @Autowired LeaderboardProperties properties;
    @Autowired BidRepository bids;
    @Autowired AuctionRepository auctions;
    @Autowired AuctionService auctionService;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    @Test
    void unknownAuction_is404() {
        assertThatThrownBy(() -> service.getAuctionLeaderboard(UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUCTION_NOT_FOUND));
    }

    @Test
    void anAuctionWithoutBids_isEmpty_andNothingIsCached() {
        UUID auction = create(0);

        assertThat(service.getAuctionLeaderboard(auction)).isEmpty();
        assertThat(redis.hasKey(key(auction))).isFalse();
    }

    @Test
    void emptyCache_isRebuiltFromTheDatabase_withRanksAndNames_andThenCached() {
        UUID auction = create(0);
        User ann = bidder("Ann");
        User bob = bidder("Bob");
        User cat = bidder("Cat");
        bid(auction, ann, 100_000);
        bid(auction, bob, 110_000);
        bid(auction, ann, 120_000);   // Ann outbids herself: one line, her highest
        bid(auction, cat, 115_000);

        List<LeaderboardEntryResponse> top = service.getAuctionLeaderboard(auction);

        assertThat(top).containsExactly(
                new LeaderboardEntryResponse(1, "Ann", 120_000),
                new LeaderboardEntryResponse(2, "Cat", 115_000),
                new LeaderboardEntryResponse(3, "Bob", 110_000));
        assertThat(board.getLeaderboard(auction)).containsExactly(
                new LeaderboardEntry(ann.getId(), 120_000), new LeaderboardEntry(cat.getId(), 115_000), new LeaderboardEntry(bob.getId(), 110_000));
    }

    @Test
    void onlyTheTopNBiddersAreShown() {
        UUID auction = create(0);
        int topN = properties.topN();
        for (int i = 1; i <= topN + 3; i++) {
            bid(auction, bidder("Bidder" + i), 100_000L + i * 1_000L);
        }

        List<LeaderboardEntryResponse> top = service.getAuctionLeaderboard(auction);

        assertThat(top).hasSize(topN);
        assertThat(top.getFirst()).isEqualTo(new LeaderboardEntryResponse(1, "Bidder" + (topN + 3), 100_000L + (topN + 3) * 1_000L));
        assertThat(top.getLast().bidderName()).isEqualTo("Bidder4");
    }

    @Test
    void whenTheCacheHasEntries_itIsServedWithoutTouchingTheDatabaseRows() {
        UUID auction = create(0);
        User ann = bidder("Ann");
        bid(auction, ann, 100_000);
        // the cache says 999_000; a database read would say 100_000
        board.setLeaderboard(auction, List.of(new LeaderboardEntry(ann.getId(), 999_000)));

        assertThat(service.getAuctionLeaderboard(auction))
                .containsExactly(new LeaderboardEntryResponse(1, "Ann", 999_000));
    }

    @Test
    void whenRedisIsDown_theLeaderboardStillComesFromTheDatabase() {
        UUID auction = create(0);
        User ann = bidder("Ann");
        User bob = bidder("Bob");
        bid(auction, ann, 100_000);
        bid(auction, bob, 110_000);
        LettuceConnectionFactory dead = new LettuceConnectionFactory("localhost", 1);
        dead.afterPropertiesSet();
        try {
            AuctionLeaderBoard broken = new AuctionLeaderBoard(new StringRedisTemplate(dead), properties);
            LeaderboardService noRedis = new LeaderboardService(properties, broken, bids, auctions, users);

            assertThat(noRedis.getAuctionLeaderboard(auction)).containsExactly(
                    new LeaderboardEntryResponse(1, "Bob", 110_000), new LeaderboardEntryResponse(2, "Ann", 100_000));
        } finally {
            dead.destroy();
        }
    }

    private static String key(UUID auction) {
        return "auction:leaderboard:" + auction;
    }

    private User bidder(String name) {
        return users.save(new User("lb-" + UUID.randomUUID() + "@test.dev", "hash", name, UserRole.CUSTOMER));
    }

    private UUID create(int seat) {
        Fixture f = AuctionFixtures.create(users, venues, events, zones, jdbc, 10, true);
        return auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), f.seatedZoneId(), f.seatIds().get(seat), 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), null, null)).publicId();
    }

    /** An accepted bid written straight into the table, counted on the auction like the real bid path does. */
    private void bid(UUID auction, User bidder, long amount) {
        jdbc.update("insert into bids (auction_id, bidder_id, amount) select id, ?, ? from auctions where public_id = ?",
                bidder.getId(), amount, auction);
        jdbc.update("update auctions set bid_count = bid_count + 1 where public_id = ?", auction);
    }
}
