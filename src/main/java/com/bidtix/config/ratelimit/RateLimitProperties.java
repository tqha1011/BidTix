package com.bidtix.config.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * {@code bidtix.rate-limit.*} in application.yaml. A policy says how many requests are allowed; endpoints pick one by
 * name with {@link RateLimited}.
 *
 * @param enabled false turns every limit off (the interceptor lets everything through)
 */
@Validated
@ConfigurationProperties("bidtix.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Map<String, @Valid @NotNull Policy> policies) {

    /** The policy applied to every logged-in request that has no {@link RateLimited} annotation. */
    public static final String DEFAULT_USER_POLICY = "default-user";

    public enum Algorithm {
        /** At most {@code limit} requests per {@code window}; the counter starts at the first request. */
        FIXED_WINDOW,
        /** A bucket of {@code capacity} tokens, one per request, refilled by {@code refillTokens} every {@code refillEvery}. */
        TOKEN_BUCKET
    }

    /**
     * One policy. Only the fields of its {@code algorithm} are used (and checked):
     * <pre>
     * login:        { limit: 5, window: 1m, fail-open: false }                                    # fixed window
     * default-user: { algorithm: token-bucket, capacity: 15, refill-tokens: 3, refill-every: 10s } # token bucket
     * </pre>
     *
     * @param limit        FIXED_WINDOW: requests allowed per window
     * @param window       FIXED_WINDOW: length of the window, e.g. {@code 1m}
     * @param capacity     TOKEN_BUCKET: most tokens the bucket holds, which is also the largest burst
     * @param refillTokens TOKEN_BUCKET: tokens added every {@code refillEvery} (never above the capacity)
     * @param refillEvery  TOKEN_BUCKET: how often the tokens are added, e.g. {@code 10s}
     * @param failOpen     what to do when Redis cannot be reached: true = let the request through, false = reject it
     */
    public record Policy(
            @DefaultValue("FIXED_WINDOW") Algorithm algorithm,
            int limit,
            Duration window,
            int capacity,
            int refillTokens,
            Duration refillEvery,
            @DefaultValue("true") boolean failOpen) {

        /** Checked when the properties are bound, so a typo in application.yaml stops the start instead of failing later. */
        public Policy {
            if (algorithm == Algorithm.FIXED_WINDOW) {
                require(limit >= 1, "limit must be at least 1");
                require(positive(window), "window must be a positive duration, e.g. 1m");
            } else {
                require(capacity >= 1, "capacity must be at least 1");
                require(refillTokens >= 1, "refill-tokens must be at least 1");
                require(positive(refillEvery), "refill-every must be a positive duration, e.g. 10s");
            }
        }

        public static Policy fixedWindow(int limit, Duration window, boolean failOpen) {
            return new Policy(Algorithm.FIXED_WINDOW, limit, window, 0, 0, null, failOpen);
        }

        public static Policy tokenBucket(int capacity, int refillTokens, Duration refillEvery, boolean failOpen) {
            return new Policy(Algorithm.TOKEN_BUCKET, 0, null, capacity, refillTokens, refillEvery, failOpen);
        }

        private static boolean positive(Duration duration) {
            return duration != null && !duration.isZero() && !duration.isNegative();
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalArgumentException("Invalid rate limit policy: " + message);
            }
        }
    }
}
