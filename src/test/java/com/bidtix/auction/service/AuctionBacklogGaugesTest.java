package com.bidtix.auction.service;

import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.infrastructure.AuctionBacklogGauges;
import com.bidtix.auction.service.AuctionFixtures.Fixture;
import com.bidtix.event.service.EventService;
import com.bidtix.event.service.VenueService;
import com.bidtix.event.service.ZoneService;
import com.bidtix.support.PostgresIntegrationTest;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.infrastructure.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database is shared and the scheduled jobs run, so every count is compared with what it was before, and the rows
 * added here are ones no job can deal with.
 */
class AuctionBacklogGaugesTest extends PostgresIntegrationTest {

    @Autowired AuctionBacklogGauges gauges;
    @Autowired MeterRegistry meters;
    @Autowired AuctionService auctionService;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> auctions = new ArrayList<>();

    /** Otherwise the jobs keep failing on these rows for as long as the tests run. */
    @AfterEach
    void cleanUp() {
        auctions.forEach(id -> jdbc.update("update auctions set status = 'CANCELLED' where public_id = ?", id));
    }

    @Test
    void auctionsStillLive5MinutesAfterTheirEndAreCounted() {
        Fixture f = fixture();
        double before = read("bidtix.auctions.overdue");

        unclosable(f, "6 minutes");
        unclosable(f, "4 minutes");   // the closing job runs every minute: not late yet

        assertThat(read("bidtix.auctions.overdue")).isEqualTo(before + 1);
    }

    @Test
    void eventsCancelled10MinutesAgoWhoseAuctionsAreNotCleanedUpAreCounted() {
        double before = readUncleaned();

        cancelledAndStuck("11 minutes");
        cancelledAndStuck("9 minutes");

        assertThat(readUncleaned()).isEqualTo(before + 1);
    }

    private Fixture fixture() {
        return AuctionFixtures.create(users, venues, events, zones, jdbc, 10, true);
    }

    private UUID auction(Fixture f) {
        Instant now = Instant.now();
        UUID id = auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), f.standingZoneId(), null, 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), null, null)).publicId();
        auctions.add(id);
        return id;
    }

    /** A leader and a price but no bid row behind them: the closing job fails on it and leaves it LIVE. */
    private void unclosable(Fixture f, String endedAgo) {
        UUID id = auction(f);
        User leader = users.save(new User("leader-" + UUID.randomUUID() + "@test.dev", "hash", "Leader",
                UserRole.CUSTOMER));
        jdbc.update("update auctions set status = 'LIVE', starts_at = now() - interval '2 hours', "
                + "ends_at = now() - cast(? as interval), current_price = 140000, bid_count = 1, leading_bidder_id = ? "
                + "where public_id = ?", endedAgo, leader.getId(), id);
    }

    /** An auction awaiting payment without an offer: the job leaves it, so the event stays unmarked. */
    private void cancelledAndStuck(String ago) {
        Fixture f = fixture();
        UUID id = auction(f);
        jdbc.update("update auctions set status = 'AWAITING_PAYMENT' where public_id = ?", id);
        jdbc.update("update events set status = 'CANCELLED', cancelled_at = now() - cast(? as interval) "
                + "where public_id = ?", ago, f.eventId());
    }

    private double readUncleaned() {
        gauges.refresh();
        return meters.get("bidtix.events.cancelled.uncleaned").tag("module", "auction").gauge().value();
    }

    private double read(String gauge) {
        gauges.refresh();
        return meters.get(gauge).gauge().value();
    }
}
