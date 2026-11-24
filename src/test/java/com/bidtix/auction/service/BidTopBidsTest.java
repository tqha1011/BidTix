package com.bidtix.auction.service;

import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.domain.LeaderboardEntry;
import com.bidtix.auction.infrastructure.BidRepository;
import com.bidtix.auction.service.AuctionFixtures.Fixture;
import com.bidtix.event.service.EventService;
import com.bidtix.event.service.VenueService;
import com.bidtix.event.service.ZoneService;
import com.bidtix.support.PostgresIntegrationTest;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code findTopBids}: one line per bidder (their id) with their highest bid, highest first, only for the asked auction. */
class BidTopBidsTest extends PostgresIntegrationTest {

    @Autowired BidRepository bids;
    @Autowired AuctionService auctionService;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    @Test
    void countsEachBidderOnceWithTheirHighestBid_highestFirst_andOnlyAsManyAsAsked() {
        Fixture f = AuctionFixtures.create(users, venues, events, zones, jdbc, 10, true);
        UUID auction = create(f, 0);
        User ann = bidder("Ann");
        User bob = bidder("Bob");
        User cat = bidder("Cat");
        User dee = bidder("Dee");
        bid(auction, ann, 100_000);
        bid(auction, bob, 110_000);
        bid(auction, ann, 120_000);   // Ann outbids herself: still one line, with 120_000
        bid(auction, cat, 130_000);
        bid(auction, dee, 140_000);

        List<LeaderboardEntry> top = bids.findTopBids(auction, PageRequest.of(0, 3));

        assertThat(top).containsExactly(new LeaderboardEntry(dee.getId(), 140_000L), new LeaderboardEntry(cat.getId(), 130_000L),
                new LeaderboardEntry(ann.getId(), 120_000L));
        assertThat(bids.findTopBids(auction, PageRequest.of(0, 10))).hasSize(4);
    }

    @Test
    void onlyLooksAtTheAskedAuction() {
        Fixture f = AuctionFixtures.create(users, venues, events, zones, jdbc, 10, true);
        UUID mine = create(f, 0);
        UUID other = create(f, 1);
        User eve = bidder("Eve");
        bid(mine, eve, 100_000);
        bid(other, bidder("Fay"), 900_000);

        List<LeaderboardEntry> top = bids.findTopBids(mine, PageRequest.of(0, 10));

        assertThat(top).containsExactly(new LeaderboardEntry(eve.getId(), 100_000L));
    }

    @Test
    void anAuctionWithoutBids_hasAnEmptyLeaderboard() {
        Fixture f = AuctionFixtures.create(users, venues, events, zones, jdbc, 10, true);

        assertThat(bids.findTopBids(create(f, 0), PageRequest.of(0, 10))).isEmpty();
    }

    private User bidder(String name) {
        return users.save(new User("top-" + UUID.randomUUID() + "@test.dev", "hash", name, UserRole.CUSTOMER));
    }

    private UUID create(Fixture f, int seat) {
        return auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), f.seatedZoneId(), f.seatIds().get(seat), 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), null, null)).publicId();
    }

    /** An accepted bid, written straight into the table: only the read is under test. */
    private void bid(UUID auction, User bidder, long amount) {
        jdbc.update("insert into bids (auction_id, bidder_id, amount) select id, ?, ? from auctions where public_id = ?",
                bidder.getId(), amount, auction);
    }
}
