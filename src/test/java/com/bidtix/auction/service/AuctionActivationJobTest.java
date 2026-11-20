package com.bidtix.auction.service;

import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.infrastructure.AuctionActivationJob;
import com.bidtix.auction.service.AuctionFixtures.Fixture;
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
 * The job that opens auctions: SCHEDULED -> LIVE once {@code starts_at} has passed. The application runs the same
 * job every few seconds, so these tests only look at their own auctions and never at what the job returns.
 */
class AuctionActivationJobTest extends PostgresIntegrationTest {

    @Autowired AuctionActivationJob job;
    @Autowired AuctionService auctionService;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    @Test
    void anAuctionWhoseTimeHasCome_goesLive() {
        Fixture f = fixture(10);
        UUID auction = create(f);
        long versionBefore = versionOf(auction);
        startedAMinuteAgo(auction);

        job.activateDueAuctions();

        assertThat(statusOf(auction)).isEqualTo("LIVE");
        assertThat(versionOf(auction)).as("the optimistic-locking version moves with the state change").isEqualTo(versionBefore + 1);
    }

    @Test
    void anAuctionThatHasNotStarted_staysScheduled() {
        Fixture f = fixture(10);
        UUID auction = create(f);

        job.activateDueAuctions();

        assertThat(statusOf(auction)).isEqualTo("SCHEDULED");
    }

    @Test
    void anAuctionInAnotherState_isLeftAlone() {
        Fixture f = fixture(10);
        UUID cancelled = create(f);
        startedAMinuteAgo(cancelled);
        jdbc.update("update auctions set status = 'CANCELLED' where public_id = ?", cancelled);

        job.activateDueAuctions();

        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
    }

    @Test
    void moreDueAuctionsThanOneBatch_areAllOpened() {
        int auctions = 250;   // the job opens 100 at a time
        Fixture f = fixture(auctions);
        for (int i = 0; i < auctions; i++) {
            create(f);
        }
        allStartedAMinuteAgo(f);

        job.activateDueAuctions();

        assertThat(jdbc.queryForObject("select count(*) from auctions where status = 'LIVE' and event_id = "
                + "(select id from events where public_id = ?)", Integer.class, f.eventId())).isEqualTo(auctions);
    }

    @Test
    void severalJobsAtOnce_openEachAuctionExactlyOnce() throws Exception {
        int auctions = 40;
        Fixture f = fixture(auctions);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < auctions; i++) {
            ids.add(create(f));
        }
        List<Long> versions = ids.stream().map(this::versionOf).toList();
        allStartedAMinuteAgo(f);

        int jobs = 4;
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < jobs; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                job.activateDueAuctions();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> run : runs) {
            run.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        for (int i = 0; i < auctions; i++) {
            assertThat(statusOf(ids.get(i))).isEqualTo("LIVE");
            assertThat(versionOf(ids.get(i))).as("changed exactly once").isEqualTo(versions.get(i) + 1);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Fixture fixture(int standingCapacity) {
        return AuctionFixtures.create(users, venues, events, zones, jdbc, standingCapacity, true);
    }

    /** A standing auction that starts in an hour (the service refuses a start in the past). */
    private UUID create(Fixture f) {
        return auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), f.standingZoneId(), null, 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), null, null)).publicId();
    }

    private void startedAMinuteAgo(UUID auction) {
        jdbc.update("update auctions set starts_at = now() - interval '1 minute' where public_id = ?", auction);
    }

    private void allStartedAMinuteAgo(Fixture f) {
        jdbc.update("update auctions set starts_at = now() - interval '1 minute' where event_id = "
                + "(select id from events where public_id = ?)", f.eventId());
    }

    private String statusOf(UUID auction) {
        return jdbc.queryForObject("select status from auctions where public_id = ?", String.class, auction);
    }

    private long versionOf(UUID auction) {
        return jdbc.queryForObject("select version from auctions where public_id = ?", Long.class, auction);
    }
}
