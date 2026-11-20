package com.bidtix.auction.service;

import com.bidtix.auction.domain.AuctionStatus;
import com.bidtix.auction.dtos.AuctionResponse;
import com.bidtix.auction.dtos.CreateAuctionRequest;
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
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Creating an auction: what it checks, and that it takes the ticket out of normal sale in the same transaction. */
class AuctionServiceTest extends PostgresIntegrationTest {

    @Autowired AuctionService auctionService;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    // ---- what a good request does ----------------------------------------------------------------

    @Test
    void aSeatedAuction_isScheduled_andTheSeatLeavesNormalSale() {
        Fixture f = fixture(10, true);
        UUID seat = f.seatIds().get(0);

        AuctionResponse auction = auctionService.createAuction(f.organizer().getPublicId(), seatedRequest(f, seat));

        assertThat(auction.status()).isEqualTo(AuctionStatus.SCHEDULED);
        assertThat(auction.eventPublicId()).isEqualTo(f.eventId());
        assertThat(auction.zonePublicId()).isEqualTo(f.seatedZoneId());
        assertThat(auction.seatPublicId()).isEqualTo(seat);
        assertThat(auction.startPrice()).isEqualTo(100_000);
        assertThat(auction.minIncrement()).isEqualTo(10_000);
        assertThat(auction.currentPrice()).as("no bid yet").isNull();
        assertThat(auction.bidCount()).isZero();
        assertThat(auction.antiSnipeWindowSec()).as("default").isEqualTo(60);
        assertThat(auction.extensionSec()).as("default").isEqualTo(60);

        assertThat(auctionsOf(f)).isEqualTo(1);
        assertThat(seatStatus(seat)).isEqualTo("RESERVED_FOR_AUCTION");
        assertThat(jdbc.queryForObject("select status from auctions where public_id = ?", String.class, auction.publicId()))
                .isEqualTo("SCHEDULED");
    }

    @Test
    void aStandingAuction_takesOneTicketFromTheCounter() {
        Fixture f = fixture(10, true);

        AuctionResponse auction = auctionService.createAuction(f.organizer().getPublicId(), standingRequest(f));

        assertThat(auction.seatPublicId()).isNull();
        assertThat(auction.zonePublicId()).isEqualTo(f.standingZoneId());
        assertThat(available(f)).isEqualTo(9);
        assertThat(auctionsOf(f)).isEqualTo(1);
    }

    @Test
    void theAntiSnipeSettings_theOrganizerChose_areKept() {
        Fixture f = fixture(10, true);
        CreateAuctionRequest request = with(standingRequest(f), r -> new CreateAuctionRequest(r.eventPublicId(),
                r.zonePublicId(), null, r.startPrice(), r.minIncrement(), 500_000L, r.startsAt(), r.endsAt(), 30, 120));

        AuctionResponse auction = auctionService.createAuction(f.organizer().getPublicId(), request);

        assertThat(auction.antiSnipeWindowSec()).isEqualTo(30);
        assertThat(auction.extensionSec()).isEqualTo(120);
        assertThat(jdbc.queryForObject("select reserve_price from auctions where public_id = ?", Long.class, auction.publicId()))
                .isEqualTo(500_000L);
    }

    // ---- who and when ----------------------------------------------------------------------------

    @Test
    void anotherOrganizer_isForbidden_andNothingIsTaken() {
        Fixture f = fixture(10, true);
        User stranger = users.save(new User("stranger-" + UUID.randomUUID() + "@test.dev", "hash", "Other", UserRole.ORGANIZER));

        assertThatCode(() -> auctionService.createAuction(stranger.getPublicId(), standingRequest(f)), ErrorCode.FORBIDDEN);
        assertThatCode(() -> auctionService.createAuction(stranger.getPublicId(), seatedRequest(f, f.seatIds().get(0))), ErrorCode.FORBIDDEN);

        assertThat(auctionsOf(f)).isZero();
        assertThat(available(f)).isEqualTo(10);
        assertThat(seatStatus(f.seatIds().get(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void aDraftEvent_cannotHaveAuctions() {
        Fixture f = fixture(10, false);

        assertThatCode(() -> auctionService.createAuction(f.organizer().getPublicId(), standingRequest(f)),
                ErrorCode.EVENT_NOT_PUBLISHED);

        assertThat(auctionsOf(f)).isZero();
        assertThat(available(f)).isEqualTo(10);
    }

    @Test
    void anUnknownEvent_isEventNotFound() {
        Fixture f = fixture(10, true);
        CreateAuctionRequest request = with(standingRequest(f), r -> new CreateAuctionRequest(UUID.randomUUID(),
                r.zonePublicId(), null, r.startPrice(), r.minIncrement(), null, r.startsAt(), r.endsAt(), null, null));

        assertThatCode(() -> auctionService.createAuction(f.organizer().getPublicId(), request), ErrorCode.EVENT_NOTFOUND);
    }

    @Test
    void aZoneOfAnotherEvent_isZoneNotFound() {
        Fixture f = fixture(10, true);
        Fixture other = fixture(10, true);
        CreateAuctionRequest request = with(standingRequest(f), r -> new CreateAuctionRequest(r.eventPublicId(),
                other.standingZoneId(), null, r.startPrice(), r.minIncrement(), null, r.startsAt(), r.endsAt(), null, null));

        assertThatCode(() -> auctionService.createAuction(f.organizer().getPublicId(), request), ErrorCode.ZONE_NOTFOUND);

        assertThat(available(other)).as("the other event's counter was not touched").isEqualTo(10);
    }

    // ---- rules of the request --------------------------------------------------------------------

    @Test
    void aReservePriceBelowTheStartPrice_isRejected() {
        Fixture f = fixture(10, true);
        assertRejected(f, r -> new CreateAuctionRequest(r.eventPublicId(), r.zonePublicId(), null, 100_000L, 10_000L,
                99_999L, r.startsAt(), r.endsAt(), null, null));
    }

    @Test
    void anAuctionThatEndsBeforeItStarts_isRejected() {
        Fixture f = fixture(10, true);
        assertRejected(f, r -> new CreateAuctionRequest(r.eventPublicId(), r.zonePublicId(), null, r.startPrice(),
                r.minIncrement(), null, r.startsAt(), r.startsAt(), null, null));
    }

    @Test
    void anAuctionThatStartsInThePast_isRejected() {
        Fixture f = fixture(10, true);
        assertRejected(f, r -> new CreateAuctionRequest(r.eventPublicId(), r.zonePublicId(), null, r.startPrice(),
                r.minIncrement(), null, now.minus(1, ChronoUnit.MINUTES), r.endsAt(), null, null));
    }

    @Test
    void anAuctionThatEndsAfterTheShowStarts_isRejected() {
        Fixture f = fixture(10, true);
        assertRejected(f, r -> new CreateAuctionRequest(r.eventPublicId(), r.zonePublicId(), null, r.startPrice(),
                r.minIncrement(), null, r.startsAt(), f.eventStartsAt().plus(1, ChronoUnit.MINUTES), null, null));
    }

    @Test
    void aSeatedZoneNeedsASeat() {
        Fixture f = fixture(10, true);
        CreateAuctionRequest request = with(seatedRequest(f, f.seatIds().get(0)), r -> new CreateAuctionRequest(
                r.eventPublicId(), r.zonePublicId(), null, r.startPrice(), r.minIncrement(), null, r.startsAt(), r.endsAt(), null, null));

        assertThatCode(() -> auctionService.createAuction(f.organizer().getPublicId(), request), ErrorCode.INVALID_REQUEST);
        assertThat(auctionsOf(f)).isZero();
    }

    @Test
    void aStandingZoneTakesNoSeat() {
        Fixture f = fixture(10, true);
        CreateAuctionRequest request = with(standingRequest(f), r -> new CreateAuctionRequest(r.eventPublicId(),
                r.zonePublicId(), f.seatIds().get(0), r.startPrice(), r.minIncrement(), null, r.startsAt(), r.endsAt(), null, null));

        assertThatCode(() -> auctionService.createAuction(f.organizer().getPublicId(), request), ErrorCode.INVALID_REQUEST);

        assertThat(available(f)).isEqualTo(10);
        assertThat(seatStatus(f.seatIds().get(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void aSeatThatIsNotInTheZone_isRejected() {
        Fixture f = fixture(10, true);
        Fixture other = fixture(10, true);

        assertThatCode(() -> auctionService.createAuction(f.organizer().getPublicId(), seatedRequest(f, other.seatIds().get(0))),
                ErrorCode.INVALID_REQUEST);

        assertThat(seatStatus(other.seatIds().get(0))).isEqualTo("AVAILABLE");
        assertThat(auctionsOf(f)).isZero();
    }

    // ---- the ticket must really be free ----------------------------------------------------------

    @Test
    void aSoldSeat_cannotBeAuctioned_andNoAuctionIsLeftBehind() {
        Fixture f = fixture(10, true);
        UUID seat = f.seatIds().get(0);
        jdbc.update("update seats set status = 'SOLD' where public_id = ?", seat);

        assertThatCode(() -> auctionService.createAuction(f.organizer().getPublicId(), seatedRequest(f, seat)),
                ErrorCode.SEAT_UNAVAILABLE);

        assertThat(auctionsOf(f)).as("the auction rolled back with the failed reservation").isZero();
        assertThat(seatStatus(seat)).isEqualTo("SOLD");
    }

    @Test
    void aSeatAlreadyInAnAuction_cannotBeAuctionedAgain() {
        Fixture f = fixture(10, true);
        UUID seat = f.seatIds().get(0);
        auctionService.createAuction(f.organizer().getPublicId(), seatedRequest(f, seat));

        assertThatCode(() -> auctionService.createAuction(f.organizer().getPublicId(), seatedRequest(f, seat)),
                ErrorCode.SEAT_UNAVAILABLE);

        assertThat(auctionsOf(f)).isEqualTo(1);
    }

    @Test
    void aSoldOutStandingZone_cannotBeAuctioned_andNoAuctionIsLeftBehind() {
        Fixture f = fixture(10, true);
        jdbc.update("update ticket_zones set available_quantity = 0 where id = ?", f.standingZoneDbId());

        assertThatCode(() -> auctionService.createAuction(f.organizer().getPublicId(), standingRequest(f)),
                ErrorCode.ZONE_SOLD_OUT);

        assertThat(auctionsOf(f)).isZero();
        assertThat(available(f)).isZero();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Fixture fixture(int standingCapacity, boolean publish) {
        return AuctionFixtures.create(users, venues, events, zones, jdbc, standingCapacity, publish);
    }

    private CreateAuctionRequest seatedRequest(Fixture f, UUID seat) {
        return new CreateAuctionRequest(f.eventId(), f.seatedZoneId(), seat, 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), null, null);
    }

    private CreateAuctionRequest standingRequest(Fixture f) {
        return new CreateAuctionRequest(f.eventId(), f.standingZoneId(), null, 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), null, null);
    }

    private static CreateAuctionRequest with(CreateAuctionRequest request, UnaryOperator<CreateAuctionRequest> change) {
        return change.apply(request);
    }

    /** A standing request changed by {@code change} must be refused as INVALID_REQUEST, leaving no auction and the counter alone. */
    private void assertRejected(Fixture f, UnaryOperator<CreateAuctionRequest> change) {
        CreateAuctionRequest request = change.apply(standingRequest(f));

        assertThatCode(() -> auctionService.createAuction(f.organizer().getPublicId(), request), ErrorCode.INVALID_REQUEST);

        assertThat(auctionsOf(f)).isZero();
        assertThat(available(f)).isEqualTo(10);
    }

    private static void assertThatCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private int auctionsOf(Fixture f) {
        return jdbc.queryForObject("select count(*) from auctions a join events e on e.id = a.event_id where e.public_id = ?",
                Integer.class, f.eventId());
    }

    private int available(Fixture f) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where id = ?", Integer.class, f.standingZoneDbId());
    }

    private String seatStatus(UUID seat) {
        return jdbc.queryForObject("select status from seats where public_id = ?", String.class, seat);
    }
}
