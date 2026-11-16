package com.bidtix.config.ratelimit;

import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Counts requests per (policy, subject) in Redis. Two algorithms, picked per policy in application.yaml:
 * <ul>
 *   <li><b>fixed window</b>: the first request creates a counter that lives for one window, every request adds one,
 *       the counter disappears when the window ends;</li>
 *   <li><b>token bucket</b>: a bucket of tokens, one is taken per request, and tokens are added back over time up to
 *       the capacity. The refill is not a background job: when a request arrives the script works out how many
 *       refill periods have passed since the last one and adds that many (so an idle bucket costs nothing).</li>
 * </ul>
 * Each is one Lua script, so two requests cannot both see the same state. Counting in Java (read, then write) would
 * race the same way the seat-hold benchmark's naive strategy does.
 */
@Slf4j
@Service
public class RateLimitService {

    private static final String KEY_PREFIX = "bidtix-backend:ratelimit:";

    /** KEYS[1] = counter, ARGV[1] = window in ms. Returns {count, ms until the window ends}. */
    private static final String FIXED_WINDOW_LUA = """
            local count = redis.call('INCR', KEYS[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if count == 1 or ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return {count, ttl}
            """;

    /**
     * KEYS[1] = bucket (a hash: tokens, ts), ARGV = capacity, tokens per refill, refill period in ms.
     * Returns {1 or 0 = allowed, tokens left, ms until the next token (0 when allowed)}.
     * Time comes from Redis (TIME), not from the application, so instances with different clocks agree.
     * {@code ts} is the moment up to which refills have been paid out; it moves in whole periods, so a partly
     * elapsed period is not lost, and it jumps to "now" when the bucket is full (idle time earns nothing extra).
     */
    private static final String TOKEN_BUCKET_LUA = """
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local every = tonumber(ARGV[3])
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)

            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            else
              local periods = math.floor((now - ts) / every)
              if periods > 0 then
                tokens = math.min(capacity, tokens + periods * refill)
                ts = ts + periods * every
                if tokens >= capacity then ts = now end
              end
            end

            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', ts)

            -- once the bucket would be full again the key is the same as a missing one, so let it expire
            local periodsToFull = math.ceil((capacity - tokens) / refill)
            redis.call('PEXPIRE', KEYS[1], periodsToFull * every)

            local retry = 0
            if allowed == 0 then retry = ts + every - now end
            return {allowed, tokens, retry}
            """;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>(FIXED_WINDOW_LUA, List.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, List.class);

    /**
     * @param limit             the window's limit, or the bucket's capacity
     * @param remaining         requests left in this window, or tokens left in the bucket
     * @param retryAfterSeconds when blocked, how long until a request can pass again (at least 1)
     */
    public record Decision(boolean allowed, int limit, long remaining, long retryAfterSeconds) {
    }

    private final RateLimitProperties properties;
    private final StringRedisTemplate redis;

    public RateLimitService(RateLimitProperties properties, StringRedisTemplate redis) {
        this.properties = properties;
        this.redis = redis;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * Counts one request.
     *
     * @throws BusinessException {@code RATE_LIMITER_UNAVAILABLE} when Redis cannot be reached and the policy is
     *                           fail-closed; with a fail-open policy the request is allowed instead
     * @throws IllegalStateException when the policy name is not in application.yaml (a bug in the annotation)
     */
    public Decision check(String policyName, String subject) {
        RateLimitProperties.Policy policy = properties.policies().get(policyName);
        if (policy == null) {
            throw new IllegalStateException("No rate limit policy named '" + policyName
                    + "' under bidtix.rate-limit.policies");
        }

        String key = KEY_PREFIX + policyName + ":" + subject;
        List<?> result;
        try {
            result = policy.algorithm() == RateLimitProperties.Algorithm.TOKEN_BUCKET
                    ? redis.execute(TOKEN_BUCKET_SCRIPT, List.of(key), String.valueOf(policy.capacity()),
                            String.valueOf(policy.refillTokens()), String.valueOf(policy.refillEvery().toMillis()))
                    : redis.execute(FIXED_WINDOW_SCRIPT, List.of(key), String.valueOf(policy.window().toMillis()));
        } catch (DataAccessException e) {
            return whenRedisIsDown(policyName, policy, e);
        }
        if (result == null || result.size() < 2) {
            return whenRedisIsDown(policyName, policy, new IllegalStateException("empty script result"));
        }

        return policy.algorithm() == RateLimitProperties.Algorithm.TOKEN_BUCKET
                ? bucketDecision(policy, result)
                : windowDecision(policy, result);
    }

    private static Decision windowDecision(RateLimitProperties.Policy policy, List<?> result) {
        long count = ((Number) result.get(0)).longValue();
        long ttlMillis = ((Number) result.get(1)).longValue();
        boolean allowed = count <= policy.limit();
        return new Decision(allowed, policy.limit(), Math.max(policy.limit() - count, 0),
                allowed ? 0 : seconds(ttlMillis));
    }

    private static Decision bucketDecision(RateLimitProperties.Policy policy, List<?> result) {
        boolean allowed = ((Number) result.get(0)).longValue() == 1;
        long tokens = ((Number) result.get(1)).longValue();
        long retryMillis = result.size() > 2 ? ((Number) result.get(2)).longValue() : 0;
        return new Decision(allowed, policy.capacity(), tokens, allowed ? 0 : seconds(retryMillis));
    }

    /** Whole seconds, rounded up, at least 1: a Retry-After of 0 would invite an immediate retry. */
    private static long seconds(long millis) {
        return Math.max((millis + 999) / 1000, 1);
    }

    private Decision whenRedisIsDown(String policyName, RateLimitProperties.Policy policy, Exception cause) {
        if (policy.failOpen()) {
            log.warn("Rate limiter cannot reach Redis, letting the request through (policy {}): {}",
                    policyName, cause.toString());
            int limit = policy.algorithm() == RateLimitProperties.Algorithm.TOKEN_BUCKET ? policy.capacity() : policy.limit();
            return new Decision(true, limit, limit, 0);
        }
        log.error("Rate limiter cannot reach Redis and policy {} is fail-closed, rejecting the request: {}",
                policyName, cause.toString());
        throw new BusinessException(ErrorCode.RATE_LIMITER_UNAVAILABLE);
    }
}
