package com.bidtix.auction.service;

import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.service.AuctionFixtures.Fixture;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Many organizers' requests at the same instant: one ticket is never auctioned twice, and the counter never goes below zero. */
class AuctionCreateConcurrencyTest extends PostgresIntegrationTest {

    private static final int THREADS = 12;

    @Autowired AuctionService auctionService;
    @Autowired UserRepository users;
    @Autowired VenueService venues;
    @Autowired EventService events;
    @Autowired ZoneService zones;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    @Test
    void manyRequestsForOneSeat_exactlyOneAuctionIsCreated() throws Exception {
        Fixture f = AuctionFixtures.create(users, venues, events, zones, jdbc, 10, true);
        UUID seat = f.seatIds().get(0);

        List<String> outcomes = race(() -> auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), f.seatedZoneId(), seat, 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), null, null)));

        assertThat(outcomes.stream().filter("CREATED"::equals)).as("exactly one wins").hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.equals("CREATED"))).hasSize(THREADS - 1)
                .containsOnly(ErrorCode.SEAT_UNAVAILABLE.name());
        assertThat(auctionsOf(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from seats where public_id = ?", String.class, seat))
                .isEqualTo("RESERVED_FOR_AUCTION");
    }

    @Test
    void manyRequestsForAFewStandingTickets_neverTakeMoreThanThereAre() throws Exception {
        int capacity = 3;
        Fixture f = AuctionFixtures.create(users, venues, events, zones, jdbc, capacity, true);

        List<String> outcomes = race(() -> auctionService.createAuction(f.organizer().getPublicId(), new CreateAuctionRequest(
                f.eventId(), f.standingZoneId(), null, 100_000L, 10_000L, null,
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), null, null)));

        assertThat(outcomes.stream().filter("CREATED"::equals)).as("as many as there are tickets").hasSize(capacity);
        assertThat(outcomes.stream().filter(o -> !o.equals("CREATED"))).hasSize(THREADS - capacity)
                .containsOnly(ErrorCode.ZONE_SOLD_OUT.name());
        assertThat(auctionsOf(f)).isEqualTo(capacity);
        assertThat(jdbc.queryForObject("select available_quantity from ticket_zones where id = ?", Integer.class,
                f.standingZoneDbId())).isZero();
    }

    /** Runs {@link #THREADS} identical calls released by one latch; each result is "CREATED" or the error code. */
    private static List<String> race(Runnable create) throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Callable<String> call = () -> {
                    ready.countDown();
                    go.await();
                    try {
                        create.run();
                        return "CREATED";
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

    private int auctionsOf(Fixture f) {
        return jdbc.queryForObject("select count(*) from auctions a join events e on e.id = a.event_id where e.public_id = ?",
                Integer.class, f.eventId());
    }
}
