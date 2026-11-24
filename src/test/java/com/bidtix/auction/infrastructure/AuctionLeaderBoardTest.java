package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.LeaderboardEntry;
import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** The Redis leaderboard of one auction, against a real Redis: top N kept, scores only rise, TTL set, fail-open. */
class AuctionLeaderBoardTest extends PostgresIntegrationTest {

    @Autowired
    AuctionLeaderBoard board;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    LeaderboardProperties properties;

    private static String key(UUID auction) {
        return "auction:leaderboard:" + auction;
    }

    @Test
    void unknownAuctionHasAnEmptyLeaderboard() {
        assertThat(board.getLeaderboard(UUID.randomUUID())).isEmpty();
    }

    @Test
    void returnsBiddersHighestFirstAndKeepsAllWhenThereAreFewerThanTopN() {
        UUID auction = UUID.randomUUID();

        board.setLeaderboard(auction, List.of(new LeaderboardEntry(1L, 100_000), new LeaderboardEntry(2L, 300_000), new LeaderboardEntry(3L, 200_000)));

        assertThat(board.getLeaderboard(auction)).containsExactly(
                new LeaderboardEntry(2L, 300_000), new LeaderboardEntry(3L, 200_000), new LeaderboardEntry(1L, 100_000));
    }

    @Test
    void keepsOnlyTheTopNHighestWhenGivenMore() {
        UUID auction = UUID.randomUUID();
        int topN = properties.topN();
        // bidder i bids i * 1000, so the highest topN bidders are the ones with the largest ids
        List<LeaderboardEntry> all = IntStream.rangeClosed(1, topN + 5).mapToObj(i -> new LeaderboardEntry((long) i, i * 1000L)).toList();

        board.setLeaderboard(auction, all);

        assertThat(redis.opsForZSet().zCard(key(auction))).isEqualTo(topN);
        List<LeaderboardEntry> top = board.getLeaderboard(auction);
        assertThat(top).hasSize(topN);
        assertThat(top.getFirst()).isEqualTo(new LeaderboardEntry(topN + 5L, (topN + 5) * 1000L));
        assertThat(top.getLast()).isEqualTo(new LeaderboardEntry(6L, 6000L));
    }

    @Test
    void aScoreOnlyRises() {
        UUID auction = UUID.randomUUID();
        board.setLeaderboard(auction, List.of(new LeaderboardEntry(1L, 100_000)));

        // a rebuild that read the database before a newer bid must not pull the bidder down
        board.setLeaderboard(auction, List.of(new LeaderboardEntry(1L, 50_000)));
        assertThat(board.getLeaderboard(auction)).containsExactly(new LeaderboardEntry(1L, 100_000));

        board.setLeaderboard(auction, List.of(new LeaderboardEntry(1L, 200_000)));
        assertThat(board.getLeaderboard(auction)).containsExactly(new LeaderboardEntry(1L, 200_000));
    }

    @Test
    void theKeyExpires() {
        UUID auction = UUID.randomUUID();

        board.setLeaderboard(auction, List.of(new LeaderboardEntry(1L, 100_000)));

        Long ttl = redis.getExpire(key(auction));
        assertThat(ttl).isPositive().isLessThanOrEqualTo(properties.ttlSeconds());
    }

    @Test
    void anEmptyListWritesNothing() {
        UUID auction = UUID.randomUUID();

        board.setLeaderboard(auction, List.of());

        assertThat(redis.hasKey(key(auction))).isFalse();
    }

    @Test
    void aSmallerTopNIsHonoured() {
        AuctionLeaderBoard small = new AuctionLeaderBoard(redis, new LeaderboardProperties(true, 3, 60));
        UUID auction = UUID.randomUUID();

        small.setLeaderboard(auction, List.of(new LeaderboardEntry(1L, 10), new LeaderboardEntry(2L, 20), new LeaderboardEntry(3L, 30),
                new LeaderboardEntry(4L, 40), new LeaderboardEntry(5L, 50)));

        assertThat(small.getLeaderboard(auction)).containsExactly(new LeaderboardEntry(5L, 50), new LeaderboardEntry(4L, 40), new LeaderboardEntry(3L, 30));
    }

    @Test
    void whenDisabledNothingIsReadOrWritten() {
        AuctionLeaderBoard off = new AuctionLeaderBoard(redis, new LeaderboardProperties(false, 10, 60));
        UUID auction = UUID.randomUUID();

        off.setLeaderboard(auction, List.of(new LeaderboardEntry(1L, 10)));

        assertThat(redis.hasKey(key(auction))).isFalse();
        assertThat(off.getLeaderboard(auction)).isEmpty();
    }

    @Test
    void recordBid_doesNothingWhenTheLeaderboardIsNotCached() {
        UUID auction = UUID.randomUUID();

        board.recordBid(auction, new LeaderboardEntry(1L, 100_000));

        // a ZSET holding just this bidder would look complete to the next reader and never be rebuilt
        assertThat(redis.hasKey(key(auction))).isFalse();
        assertThat(board.getLeaderboard(auction)).isEmpty();
    }

    @Test
    void recordBid_addsAndRaisesInACachedLeaderboard_butNeverLowersAScore() {
        UUID auction = UUID.randomUUID();
        board.setLeaderboard(auction, List.of(new LeaderboardEntry(1L, 100_000), new LeaderboardEntry(2L, 110_000)));

        board.recordBid(auction, new LeaderboardEntry(3L, 120_000));   // a new bidder
        board.recordBid(auction, new LeaderboardEntry(1L, 130_000));   // an existing bidder raises
        board.recordBid(auction, new LeaderboardEntry(2L, 50_000));    // a late, lower write must not pull bidder 2 down

        assertThat(board.getLeaderboard(auction)).containsExactly(
                new LeaderboardEntry(1L, 130_000), new LeaderboardEntry(3L, 120_000), new LeaderboardEntry(2L, 110_000));
    }

    @Test
    void recordBid_keepsOnlyTheTopN_andRefreshesTheTtl() {
        AuctionLeaderBoard small = new AuctionLeaderBoard(redis, new LeaderboardProperties(true, 3, 600));
        UUID auction = UUID.randomUUID();
        small.setLeaderboard(auction, List.of(new LeaderboardEntry(1L, 10), new LeaderboardEntry(2L, 20), new LeaderboardEntry(3L, 30)));
        redis.expire(key(auction), java.time.Duration.ofSeconds(5));

        small.recordBid(auction, new LeaderboardEntry(4L, 40));   // pushes bidder 1 out
        small.recordBid(auction, new LeaderboardEntry(5L, 5));    // too low to get in

        assertThat(small.getLeaderboard(auction)).containsExactly(new LeaderboardEntry(4L, 40), new LeaderboardEntry(3L, 30), new LeaderboardEntry(2L, 20));
        assertThat(redis.getExpire(key(auction))).isGreaterThan(5L).isLessThanOrEqualTo(600L);
    }

    @Test
    void recordBid_whenRedisIsDown_isIgnored() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory("localhost", 1);
        dead.afterPropertiesSet();
        try {
            AuctionLeaderBoard broken = new AuctionLeaderBoard(new StringRedisTemplate(dead),
                    new LeaderboardProperties(true, 10, 60));

            assertThatCode(() -> broken.recordBid(UUID.randomUUID(), new LeaderboardEntry(1L, 10))).doesNotThrowAnyException();
        } finally {
            dead.destroy();
        }
    }

    @Test
    void recordBid_whenDisabled_writesNothing() {
        UUID auction = UUID.randomUUID();
        board.setLeaderboard(auction, List.of(new LeaderboardEntry(1L, 100_000)));
        AuctionLeaderBoard off = new AuctionLeaderBoard(redis, new LeaderboardProperties(false, 10, 60));

        off.recordBid(auction, new LeaderboardEntry(2L, 200_000));

        assertThat(board.getLeaderboard(auction)).containsExactly(new LeaderboardEntry(1L, 100_000));
    }

    @Test
    void whenRedisIsDownReadsAreEmptyAndWritesAreIgnored() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory("localhost", 1);
        dead.afterPropertiesSet();
        try {
            AuctionLeaderBoard broken = new AuctionLeaderBoard(new StringRedisTemplate(dead),
                    new LeaderboardProperties(true, 10, 60));
            UUID auction = UUID.randomUUID();

            assertThat(broken.getLeaderboard(auction)).isEmpty();
            assertThatCode(() -> broken.setLeaderboard(auction, List.of(new LeaderboardEntry(1L, 10)))).doesNotThrowAnyException();
        } finally {
            dead.destroy();
        }
    }
}
