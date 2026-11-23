package com.bidtix.auction.service;

import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.dtos.PlaceBidRequest;
import com.bidtix.auction.dtos.PlaceBidResponse;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One bid at a time: which bids are accepted, which error a refused one gets, and the anti-sniping extension. */
class BidServiceTest extends PostgresIntegrationTest {

    // start price 100_000, min increment 10_000, anti-sniping window 60 s, extension 60 s (see AuctionFixtures users below)
    @Autowired BidService bidService;
    @Autowired AuctionService auctionService;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    @Test
    void theFirstBid_mustReachTheStartPrice() {
        UUID auction = liveAuction(3600);
        User bidder = bidder();

        assertThatThrownBy(() -> bidService.placeBid(PlaceBidRequest.create(auction, 99_999L), bidder.getPublicId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_MONEY_BID));
        assertThat(bidCount(auction)).isZero();

        PlaceBidResponse response = bidService.placeBid(PlaceBidRequest.create(auction, 100_000L), bidder.getPublicId());

        assertThat(response.amount()).isEqualTo(100_000L);
        assertThat(response.auctionPublicId()).isEqualTo(auction);
        assertThat(response.bidderPublicId()).isEqualTo(bidder.getPublicId());
        assertThat(priceOf(auction)).isEqualTo(100_000L);
        assertThat(leaderOf(auction)).isEqualTo(bidder.getId());
        assertThat(bidCount(auction)).isEqualTo(1);
        assertThat(storedBids(auction)).isEqualTo(1);
    }

    @Test
    void aLaterBid_mustBeatThePriceByTheMinimumIncrement() {
        UUID auction = liveAuction(3600);
        User first = bidder();
        User second = bidder();
        bidService.placeBid(PlaceBidRequest.create(auction, 100_000L), first.getPublicId());

        assertThatThrownBy(() -> bidService.placeBid(PlaceBidRequest.create(auction, 109_999L), second.getPublicId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_MONEY_BID));
        assertThat(priceOf(auction)).isEqualTo(100_000L);
        assertThat(leaderOf(auction)).isEqualTo(first.getId());

        bidService.placeBid(PlaceBidRequest.create(auction, 110_000L), second.getPublicId());

        assertThat(priceOf(auction)).isEqualTo(110_000L);
        assertThat(leaderOf(auction)).isEqualTo(second.getId());
        assertThat(bidCount(auction)).isEqualTo(2);
        assertThat(storedBids(auction)).isEqualTo(2);
    }

    @Test
    void anAuctionThatIsOver_isRefused() {
        UUID auction = liveAuction(-60);

        assertThatThrownBy(() -> bidService.placeBid(PlaceBidRequest.create(auction, 100_000L), bidder().getPublicId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BID_NO_LONGER_AVAILABLE));
        assertThat(storedBids(auction)).isZero();
    }

    @Test
    void anAuctionThatIsNotLiveYet_isRefused() {
        Fixture f = fixture();
        UUID auction = create(f); // SCHEDULED

        assertThatThrownBy(() -> bidService.placeBid(PlaceBidRequest.create(auction, 100_000L), bidder().getPublicId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BID_NO_LONGER_AVAILABLE));
        assertThat(storedBids(auction)).isZero();
    }

    @Test
    void anUnknownAuction_isNotFound() {
        assertThatThrownBy(() -> bidService.placeBid(PlaceBidRequest.create(UUID.randomUUID(), 100_000L), bidder().getPublicId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUCTION_NOT_FOUND));
    }

    @Test
    void aBidInsideTheAntiSnipingWindow_extendsTheEnd() {
        UUID auction = liveAuction(30); // 30 s left, window 60 s
        Instant before = endsAt(auction);

        bidService.placeBid(PlaceBidRequest.create(auction, 100_000L), bidder().getPublicId());

        assertThat(endsAt(auction)).isEqualTo(before.plusSeconds(60));
    }

    @Test
    void aBidOutsideTheWindow_leavesTheEndAlone() {
        UUID auction = liveAuction(3600);
        Instant before = endsAt(auction);

        bidService.placeBid(PlaceBidRequest.create(auction, 100_000L), bidder().getPublicId());

        assertThat(endsAt(auction)).isEqualTo(before);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Fixture fixture() {
        return AuctionFixtures.create(users, venues, events, zones, jdbc, 10, true);
    }

    private User bidder() {
        return users.save(new User("bidder-" + UUID.randomUUID() + "@test.dev", "hash", "Bidder", UserRole.CUSTOMER));
    }

    private UUID create(Fixture f) {
        return auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), f.standingZoneId(), null, 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), 60, 60)).publicId();
    }

    /** LIVE, with {@code secondsLeft} until it ends (negative: already over). */
    private UUID liveAuction(int secondsLeft) {
        UUID auction = create(fixture());
        jdbc.update("update auctions set status = 'LIVE', starts_at = now() - interval '2 hours', "
                + "ends_at = now() + interval '1 second' * ? where public_id = ?", secondsLeft, auction);
        return auction;
    }

    private long priceOf(UUID auction) {
        return jdbc.queryForObject("select current_price from auctions where public_id = ?", Long.class, auction);
    }

    private long leaderOf(UUID auction) {
        return jdbc.queryForObject("select leading_bidder_id from auctions where public_id = ?", Long.class, auction);
    }

    private int bidCount(UUID auction) {
        return jdbc.queryForObject("select bid_count from auctions where public_id = ?", Integer.class, auction);
    }

    private int storedBids(UUID auction) {
        return jdbc.queryForObject("select count(*) from bids b join auctions a on a.id = b.auction_id where a.public_id = ?",
                Integer.class, auction);
    }

    private Instant endsAt(UUID auction) {
        return jdbc.queryForObject("select ends_at from auctions where public_id = ?", java.sql.Timestamp.class, auction).toInstant();
    }
}
