package com.bidtix.auction.service;

import com.bidtix.auction.domain.BidPlacedEvent;
import com.bidtix.auction.domain.LeaderboardEntry;
import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.dtos.PlaceBidRequest;
import com.bidtix.auction.infrastructure.AuctionLeaderBoard;
import com.bidtix.auction.service.AuctionFixtures.Fixture;
import com.bidtix.config.exceptions.BusinessException;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A committed bid reaches a cached leaderboard; a rolled-back or refused one does not; an uncached one is left alone. */
class LeaderboardBidFlowTest extends PostgresIntegrationTest {

    @Autowired BidService bidService;
    @Autowired LeaderboardService leaderboardService;
    @Autowired AuctionLeaderBoard board;
    @Autowired StringRedisTemplate redis;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired AuctionService auctionService;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    @Test
    void aCommittedBid_updatesTheCachedLeaderboard() {
        UUID auction = liveAuction();
        User ann = bidder("Ann");
        User bob = bidder("Bob");
        bidService.placeBid(PlaceBidRequest.create(auction, 100_000L), ann.getPublicId());
        leaderboardService.getAuctionLeaderboard(auction);   // reads the database and caches the result
        assertThat(board.getLeaderboard(auction)).containsExactly(new LeaderboardEntry(ann.getId(), 100_000));

        bidService.placeBid(PlaceBidRequest.create(auction, 110_000L), bob.getPublicId());
        bidService.placeBid(PlaceBidRequest.create(auction, 120_000L), ann.getPublicId());

        assertThat(board.getLeaderboard(auction)).containsExactly(
                new LeaderboardEntry(ann.getId(), 120_000), new LeaderboardEntry(bob.getId(), 110_000));
        assertThat(leaderboardService.getAuctionLeaderboard(auction)).extracting("bidderName").containsExactly("Ann", "Bob");
    }

    @Test
    void aBidBeforeAnyReadDoesNotCreateAOnePersonLeaderboard() {
        UUID auction = liveAuction();
        User ann = bidder("Ann");
        User bob = bidder("Bob");

        bidService.placeBid(PlaceBidRequest.create(auction, 100_000L), ann.getPublicId());
        bidService.placeBid(PlaceBidRequest.create(auction, 110_000L), bob.getPublicId());

        assertThat(redis.hasKey("auction:leaderboard:" + auction)).isFalse();
        // the first read still sees everybody, because it rebuilds from the database
        assertThat(leaderboardService.getAuctionLeaderboard(auction)).hasSize(2);
    }

    @Test
    void aRefusedBid_doesNotTouchTheLeaderboard() {
        UUID auction = liveAuction();
        User ann = bidder("Ann");
        bidService.placeBid(PlaceBidRequest.create(auction, 100_000L), ann.getPublicId());
        leaderboardService.getAuctionLeaderboard(auction);

        assertThatThrownBy(() -> bidService.placeBid(PlaceBidRequest.create(auction, 100_001L), bidder("Bob").getPublicId()))
                .isInstanceOf(BusinessException.class);

        assertThat(board.getLeaderboard(auction)).containsExactly(new LeaderboardEntry(ann.getId(), 100_000));
    }

    @Test
    void aRolledBackBid_neverReachesTheLeaderboard() {
        UUID auction = liveAuction();
        User ann = bidder("Ann");
        bidService.placeBid(PlaceBidRequest.create(auction, 100_000L), ann.getPublicId());
        leaderboardService.getAuctionLeaderboard(auction);

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new BidPlacedEvent(auction, bidder("Bob").getId(), 500_000));
            status.setRollbackOnly();
        });

        assertThat(board.getLeaderboard(auction)).containsExactly(new LeaderboardEntry(ann.getId(), 100_000));
    }

    @Test
    void theEventIsOnlyHandledAfterTheCommit() {
        UUID auction = liveAuction();
        User ann = bidder("Ann");
        bidService.placeBid(PlaceBidRequest.create(auction, 100_000L), ann.getPublicId());
        leaderboardService.getAuctionLeaderboard(auction);
        User bob = bidder("Bob");

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new BidPlacedEvent(auction, bob.getId(), 200_000));
            // still inside the transaction: Redis must not have it yet
            assertThat(board.getLeaderboard(auction)).containsExactly(new LeaderboardEntry(ann.getId(), 100_000));
        });

        assertThat(board.getLeaderboard(auction)).containsExactly(
                new LeaderboardEntry(bob.getId(), 200_000), new LeaderboardEntry(ann.getId(), 100_000));
    }

    private User bidder(String name) {
        return users.save(new User("flow-" + UUID.randomUUID() + "@test.dev", "hash", name, UserRole.CUSTOMER));
    }

    /** LIVE for an hour. */
    private UUID liveAuction() {
        Fixture f = AuctionFixtures.create(users, venues, events, zones, jdbc, 10, true);
        UUID auction = auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), f.standingZoneId(), null, 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), 60, 60)).publicId();
        jdbc.update("update auctions set status = 'LIVE', starts_at = now() - interval '2 hours', "
                + "ends_at = now() + interval '1 hour' where public_id = ?", auction);
        return auction;
    }
}
