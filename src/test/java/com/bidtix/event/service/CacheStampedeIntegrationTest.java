package com.bidtix.event.service;

import com.bidtix.config.CacheConfig;
import com.bidtix.event.dtos.CreateVenueRequest;
import com.bidtix.event.dtos.EventResponse;
import com.bidtix.event.dtos.VenueResponse;
import com.bidtix.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

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

/**
 * Cache stampede: many requests find the same entry missing at the same moment. Without protection every one of
 * them loads it from PostgreSQL. With {@code @Cacheable(sync = true)} on a locking cache writer one request loads it
 * and the others wait for that result. The loads are counted from Hibernate's query statistics.
 */
class CacheStampedeIntegrationTest extends PostgresIntegrationTest {

    private static final int REQUESTS = 16;

    @Autowired EventService eventService;
    @Autowired VenueService venueService;
    @Autowired CacheManager cacheManager;
    @Autowired EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @Test
    void manyRequestsForAnEmptyVenueEntry_loadItFromPostgresOnce() throws Exception {
        VenueResponse venue = venueService.create(new CreateVenueRequest("Stampede Arena", "1 Street", "Hanoi"));
        statistics.clear();

        List<VenueResponse> results = allAtOnce(() -> venueService.getByPublicId(venue.publicId()));

        assertThat(results).hasSize(REQUESTS).containsOnly(venue);
        assertThat(selectsFrom("from Venue")).as("loads from PostgreSQL").isEqualTo(1);
    }

    @Test
    void manyRequestsForAnEmptyEventPage_loadItFromPostgresOnce() throws Exception {
        cacheManager.getCache(CacheConfig.EVENTS).invalidate();
        statistics.clear();

        List<Object> pages = new ArrayList<>(allAtOnce(() -> eventService.listPublished(0, 100)));

        assertThat(pages).hasSize(REQUESTS);
        assertThat(pages).as("everybody got the same page").containsOnly(pages.get(0));
        assertThat(selectsFrom("from Event")).as("loads from PostgreSQL").isEqualTo(1);
    }

    /** How many times a select (not a count) on the given entity ran since {@code statistics.clear()}. */
    private long selectsFrom(String fromClause) {
        long total = 0;
        for (String query : statistics.getQueries()) {
            String hql = query.toLowerCase();   // Hibernate reports the query in upper case: "SELECT v FROM Venue v ..."
            if (hql.contains(fromClause.toLowerCase()) && !hql.contains("count(")) {
                total += statistics.getQueryStatistics(query).getExecutionCount();
            }
        }
        return total;
    }

    /** Runs {@code call} on {@link #REQUESTS} threads that all start at the same instant. */
    private <T> List<T> allAtOnce(Callable<T> call) throws Exception {
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
