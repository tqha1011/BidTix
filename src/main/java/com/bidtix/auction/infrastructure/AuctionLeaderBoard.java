package com.bidtix.auction.infrastructure;

import com.bidtix.auction.dtos.Entry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    public List<Entry> getLeaderboard(UUID auctionPublicId){
        if(!properties.enabled()) {
            log.info("Leaderboard is disabled, returning empty list");
            return List.of();
        }
        var topN = properties.topN() > 0 ? properties.topN() : DEFAULT_TOP_N;
        var leaderboardKey = buildLeaderboardKey(auctionPublicId);

        var entries = redisTemplate.opsForZSet().reverseRangeWithScores(leaderboardKey, 0, topN - 1);
        if(entries.isEmpty()) return List.of();
        return entries.stream()
                .filter(entry -> entry.getValue() != null && entry.getScore() != null)
                .map(entry -> new Entry(
                        Long.valueOf(entry.getValue()),
                        entry.getScore().longValue())
                )
                .toList();
    }

    public void setLeaderboard(UUID auctionPublicId, List<Entry> entries){
        if(!properties.enabled()) {
            log.info("Leaderboard is disabled, not setting leaderboard");
            return;
        }
        var topN = properties.topN() > 0 ? properties.topN() : DEFAULT_TOP_N;
        var leaderboardKey = buildLeaderboardKey(auctionPublicId);
        redisTemplate.delete(leaderboardKey);
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            for (Entry entry : entries) {
                conn.zAdd(leaderboardKey, entry.amount(), String.valueOf(entry.bidderId()), RedisZSetCommands.ZAddArgs.empty().gt());
            }
            conn.zRemRange(leaderboardKey, 0, -(topN - 1));
            conn.expire(leaderboardKey, properties.ttlSeconds()); // expire in 1 hour
            return null;
        });
    }

    private String buildLeaderboardKey(UUID auctionPublicId) {
        return "auction:leaderboard:" + auctionPublicId;
    }
}
