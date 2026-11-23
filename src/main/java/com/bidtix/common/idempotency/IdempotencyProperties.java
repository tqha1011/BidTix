package com.bidtix.common.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/** Settings of {@link IdempotencyFilter}, under {@code bidtix.idempotency} in application.yaml. */
@ConfigurationProperties("bidtix.idempotency")
public record IdempotencyProperties(
        @DefaultValue("true") boolean enabled,
        /* how long a stored response can be replayed; after that the same key is a new request */
        @DefaultValue("24h") Duration ttl,
        /* a PROCESSING key older than this is taken as abandoned (the server died mid-request) and may be reused */
        @DefaultValue("2m") Duration processingTimeout,
        /* Ant patterns of the POST routes that need an Idempotency-Key, e.g. /api/v1/auctions/*&#47;bids */
        @DefaultValue List<String> paths) {
}
