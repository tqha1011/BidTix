package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.LeaderboardEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.connection.DefaultStringRedisConnection;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class AuctionLeaderBoard {
    private final StringRedisTemplate redisTemplate;
    private final LeaderboardProperties properties;

    private static final int DEFAULT_TOP_N = 10;

    /**
     * KEYS[1] the leaderboard, ARGV: score, member, topN, ttl seconds. Does nothing when the key is missing: a ZSET made
     * of just this one bidder would look complete to the next reader, which would then never rebuild it from the database.
     */
    private static final DefaultRedisScript<Long> RECORD_BID_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('ZADD', KEYS[1], 'GT', ARGV[1], ARGV[2])
            redis.call('ZREMRANGEBYRANK', KEYS[1], 0, -(tonumber(ARGV[3]) + 1))
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    /**
     * The cached top bidders, highest bid first. Empty when the cache has nothing, is disabled or is unreachable:
     * the caller cannot tell those apart and falls back to the database (Redis only ever speeds things up).
     */
    public List<LeaderboardEntry> getLeaderboard(UUID auctionPublicId) {
        if (!properties.enabled()) {
            log.debug("Leaderboard is disabled, returning empty list");
            return List.of();
        }
        var topN = topN();
        try {
            var entries = redisTemplate.opsForZSet().reverseRangeWithScores(buildLeaderboardKey(auctionPublicId), 0, topN - 1);
            if (entries == null || entries.isEmpty()) return List.of();
            return entries.stream()
                    .filter(entry -> entry.getValue() != null && entry.getScore() != null)
                    .map(entry -> new LeaderboardEntry(Long.valueOf(entry.getValue()), entry.getScore().longValue()))
                    .toList();
        } catch (DataAccessException | NumberFormatException e) {
            log.warn("Leaderboard read failed for auction {}, falling back to the database: {}", auctionPublicId, e.toString());
            return List.of();
        }
    }

    /**
     * Writes the given bidders with {@code ZADD GT}: a score is only ever raised, so a rebuild that read the database
     * a moment before a newer bid cannot push that bidder back down. The key is not deleted first for the same reason.
     * Then only the {@code topN} highest are kept and the TTL is set. Failures are logged and ignored.
     */
    public void setLeaderboard(UUID auctionPublicId, List<LeaderboardEntry> entries) {
        if (!properties.enabled()) {
            log.debug("Leaderboard is disabled, not setting leaderboard");
            return;
        }
        if (entries.isEmpty()) return;
        var topN = topN();
        var leaderboardKey = buildLeaderboardKey(auctionPublicId);
        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                // the callback gets a plain RedisConnection (a proxy), not a StringRedisConnection, so wrap it for String keys
                StringRedisConnection conn = new DefaultStringRedisConnection(connection);
                for (LeaderboardEntry entry : entries) {
                    conn.zAdd(leaderboardKey, entry.amount(), String.valueOf(entry.bidderId()), RedisZSetCommands.ZAddArgs.empty().gt());
                }
                // ranks run from the lowest score (0) up, so 0..-(topN+1) is everything except the topN highest
                conn.zRemRange(leaderboardKey, 0, -(topN + 1L));
                conn.expire(leaderboardKey, properties.ttlSeconds());
                return null;
            });
        } catch (DataAccessException e) {
            log.warn("Leaderboard write failed for auction {}, ignoring: {}", auctionPublicId, e.toString());
        }
    }

    /**
     * A bid was committed: raise this bidder's score in a leaderboard that is already cached (one Lua script, so the
     * "is it cached" check and the write cannot be split). A missing leaderboard is left alone; the next read rebuilds
     * it from the database. Failures are logged and ignored.
     */
    public void recordBid(UUID auctionPublicId, LeaderboardEntry entry) {
        if (!properties.enabled()) return;
        try {
            redisTemplate.execute(RECORD_BID_SCRIPT, List.of(buildLeaderboardKey(auctionPublicId)),
                    String.valueOf(entry.amount()), String.valueOf(entry.bidderId()),
                    String.valueOf(topN()), String.valueOf(properties.ttlSeconds()));
        } catch (DataAccessException e) {
            log.warn("Leaderboard update failed for auction {}, ignoring: {}", auctionPublicId, e.toString());
        }
    }

    private int topN() {
        return properties.topN() > 0 ? properties.topN() : DEFAULT_TOP_N;
    }

    private String buildLeaderboardKey(UUID auctionPublicId) {
        return "auction:leaderboard:" + auctionPublicId;
    }
}
