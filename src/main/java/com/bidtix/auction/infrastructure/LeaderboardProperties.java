package com.bidtix.auction.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "bidtix.leaderboard")
public record LeaderboardProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") int topN,
        @DefaultValue("3600") int ttlSeconds) {
}
