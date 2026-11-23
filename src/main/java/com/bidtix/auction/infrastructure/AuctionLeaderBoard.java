package com.bidtix.auction.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class AuctionLeaderBoard {
    private final StringRedisTemplate redisTemplate;




    private String buildLeaderboardKey(String auctionPublicId) {
        return "auction:leaderboard:" + auctionPublicId;
    }
}
