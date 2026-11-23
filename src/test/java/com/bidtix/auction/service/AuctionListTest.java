package com.bidtix.auction.service;

import com.bidtix.auction.domain.AuctionStatus;
import com.bidtix.auction.dtos.AuctionResponse;
import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.service.AuctionFixtures.Fixture;
import com.bidtix.common.dtos.PageResponse;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.service.EventService;
import com.bidtix.event.service.VenueService;
import com.bidtix.event.service.ZoneService;
import com.bidtix.support.PostgresIntegrationTest;
import com.bidtix.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Listing auctions: which ones show, the public ids in the answer, the order and the paging. */
class AuctionListTest extends PostgresIntegrationTest {

    @Autowired AuctionService auctionService;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    @Test
    void anAuction_isListedWithTheSeatedAndStandingPublicIds() {
        Fixture f = fixture();
        AuctionResponse seated = auctionService.createAuction(f.organizer().getPublicId(),
                request(f, f.seatedZoneId(), f.seatIds().get(0), 2));
        AuctionResponse standing = auctionService.createAuction(f.organizer().getPublicId(),
                request(f, f.standingZoneId(), null, 3));

        PageResponse<AuctionResponse> page = auctionService.listAuctions(null, f.eventId(), 0, 20);

        assertThat(page.totalElements()).isEqualTo(2);
        AuctionResponse first = page.items().get(0);
        assertThat(first.publicId()).isEqualTo(seated.publicId());
        assertThat(first.eventPublicId()).isEqualTo(f.eventId());
        assertThat(first.zonePublicId()).isEqualTo(f.seatedZoneId());
        assertThat(first.seatPublicId()).isEqualTo(f.seatIds().get(0));
        AuctionResponse second = page.items().get(1);
        assertThat(second.publicId()).isEqualTo(standing.publicId());
        assertThat(second.zonePublicId()).isEqualTo(f.standingZoneId());
        assertThat(second.seatPublicId()).as("a standing ticket has no seat").isNull();
    }

    @Test
    void withoutAStatus_onlyOpenAuctionsAreListed_andAStatusNarrowsToThatOne() {
        Fixture f = fixture();
        UUID scheduled = create(f, 1);
        UUID live = create(f, 2);
        UUID settled = create(f, 3);
        UUID failed = create(f, 4);
        setStatus(live, "LIVE");
        setStatus(settled, "SETTLED");
        setStatus(failed, "FAILED");

        assertThat(publicIds(auctionService.listAuctions(null, f.eventId(), 0, 20)))
                .containsExactly(scheduled, live);
        assertThat(publicIds(auctionService.listAuctions(AuctionStatus.FAILED, f.eventId(), 0, 20)))
                .containsExactly(failed);
        assertThat(publicIds(auctionService.listAuctions(AuctionStatus.SETTLED, f.eventId(), 0, 20)))
                .containsExactly(settled);
    }

    @Test
    void theEventFilter_leavesOutOtherEvents_andAnUnknownEventIsNotFound() {
        Fixture f = fixture();
        Fixture other = fixture();
        UUID mine = create(f, 1);
        create(other, 1);

        assertThat(publicIds(auctionService.listAuctions(null, f.eventId(), 0, 20))).containsExactly(mine);
        assertThatThrownBy(() -> auctionService.listAuctions(null, UUID.randomUUID(), 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOTFOUND));
    }

    @Test
    void auctionsAreOrderedByEndTime_andPaged() {
        Fixture f = fixture();
        UUID late = create(f, 5);
        UUID early = create(f, 2);
        UUID middle = create(f, 3);

        PageResponse<AuctionResponse> first = auctionService.listAuctions(null, f.eventId(), 0, 2);
        PageResponse<AuctionResponse> second = auctionService.listAuctions(null, f.eventId(), 1, 2);

        assertThat(publicIds(first)).containsExactly(early, middle);
        assertThat(publicIds(second)).containsExactly(late);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
    }

    @Test
    void theSizeAndPageAreCorrected() {
        Fixture f = fixture();
        create(f, 1);

        PageResponse<AuctionResponse> page = auctionService.listAuctions(null, f.eventId(), -3, 100_000);

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(100);
        assertThat(auctionService.listAuctions(null, f.eventId(), 0, 0).size()).isEqualTo(1);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Fixture fixture() {
        return AuctionFixtures.create(users, venues, events, zones, jdbc, 20, true);
    }

    /** A standing auction that ends {@code endsInHours} hours from now (so the order of the list is known). */
    private UUID create(Fixture f, int endsInHours) {
        return auctionService.createAuction(f.organizer().getPublicId(), request(f, f.standingZoneId(), null, endsInHours))
                .publicId();
    }

    private CreateAuctionRequest request(Fixture f, UUID zone, UUID seat, int endsInHours) {
        return new CreateAuctionRequest(f.eventId(), zone, seat, 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(endsInHours + 1L, ChronoUnit.HOURS), null, null);
    }

    private void setStatus(UUID auction, String status) {
        jdbc.update("update auctions set status = ? where public_id = ?", status, auction);
    }

    private static List<UUID> publicIds(PageResponse<AuctionResponse> page) {
        return page.items().stream().map(AuctionResponse::publicId).toList();
    }
}
