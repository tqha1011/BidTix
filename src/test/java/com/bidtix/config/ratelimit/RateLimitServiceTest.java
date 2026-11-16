package com.bidtix.config.ratelimit;

import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The decision logic without Redis: the script's answer is faked, so the edge cases can be forced. */
class RateLimitServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private RateLimitService service(boolean enabled) {
        return new RateLimitService(new RateLimitProperties(enabled, Map.of(
                "strict", RateLimitProperties.Policy.fixedWindow(5, Duration.ofMinutes(1), false),
                "lenient", RateLimitProperties.Policy.fixedWindow(5, Duration.ofMinutes(1), true),
                "bucket", RateLimitProperties.Policy.tokenBucket(15, 3, Duration.ofSeconds(10), true),
                "bucket-strict", RateLimitProperties.Policy.tokenBucket(15, 3, Duration.ofSeconds(10), false))), redis);
    }

    @SuppressWarnings("unchecked")
    private void scriptReturns(long count, long ttlMillis) {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(count, ttlMillis));
    }

    /** The token bucket script answers {allowed (1/0), tokens left, ms until the next token}. */
    @SuppressWarnings("unchecked")
    private void bucketReturns(long allowed, long tokensLeft, long retryMillis) {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(allowed, tokensLeft, retryMillis));
    }

    @Test
    void firstRequestOfAWindow_isAllowed_withLimitMinusOneLeft() {
        scriptReturns(1, 60_000);

        RateLimitService.Decision decision = service(true).check("strict", "ip:1.2.3.4");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.limit()).isEqualTo(5);
        assertThat(decision.remaining()).isEqualTo(4);
    }

    @Test
    void theLimitItself_isStillAllowed_butTheNextRequestIsNot() {
        scriptReturns(5, 30_000);
        RateLimitService.Decision last = service(true).check("strict", "ip:1.2.3.4");
        assertThat(last.allowed()).isTrue();
        assertThat(last.remaining()).isZero();

        scriptReturns(6, 30_000);
        RateLimitService.Decision blocked = service(true).check("strict", "ip:1.2.3.4");
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.remaining()).isZero();
    }

    @Test
    void retryAfter_isRoundedUpToWholeSeconds_andIsAtLeastOne() {
        scriptReturns(6, 30_001);
        assertThat(service(true).check("strict", "x").retryAfterSeconds()).isEqualTo(31);

        scriptReturns(6, 1);
        assertThat(service(true).check("strict", "x").retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void redisDown_failOpenPolicy_letsTheRequestThrough() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(service(true).check("lenient", "ip:1.2.3.4").allowed()).isTrue();
    }

    @Test
    void redisDown_failClosedPolicy_rejectsWith503() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service(true).check("strict", "ip:1.2.3.4"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITER_UNAVAILABLE));
        assertThat(ErrorCode.RATE_LIMITER_UNAVAILABLE.getStatus().value()).isEqualTo(503);
    }

    @Test
    void bucketWithTokens_allowsTheRequest_andReportsTheTokensLeft() {
        bucketReturns(1, 14, 0);

        RateLimitService.Decision decision = service(true).check("bucket", "user:1");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.limit()).as("the capacity").isEqualTo(15);
        assertThat(decision.remaining()).isEqualTo(14);
    }

    @Test
    void emptyBucket_blocks_andRetryAfterIsTheTimeToTheNextToken() {
        bucketReturns(0, 0, 7_300);

        RateLimitService.Decision decision = service(true).check("bucket", "user:1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.remaining()).isZero();
        assertThat(decision.retryAfterSeconds()).as("7.3 s rounded up").isEqualTo(8);
    }

    @Test
    void redisDown_bucketPolicy_followsItsFailOpenFlag() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        RateLimitService.Decision open = service(true).check("bucket", "user:1");
        assertThat(open.allowed()).isTrue();
        assertThat(open.limit()).isEqualTo(15);

        assertThatThrownBy(() -> service(true).check("bucket-strict", "user:1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITER_UNAVAILABLE));
    }

    @Test
    void badPolicies_areRejectedWhenTheyAreCreated() {
        assertThatThrownBy(() -> RateLimitProperties.Policy.fixedWindow(0, Duration.ofMinutes(1), true))
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> RateLimitProperties.Policy.fixedWindow(5, Duration.ZERO, true))
                .hasMessageContaining("window");
        assertThatThrownBy(() -> RateLimitProperties.Policy.fixedWindow(5, null, true))
                .hasMessageContaining("window");
        assertThatThrownBy(() -> RateLimitProperties.Policy.tokenBucket(0, 3, Duration.ofSeconds(10), true))
                .hasMessageContaining("capacity");
        assertThatThrownBy(() -> RateLimitProperties.Policy.tokenBucket(15, 0, Duration.ofSeconds(10), true))
                .hasMessageContaining("refill-tokens");
        assertThatThrownBy(() -> RateLimitProperties.Policy.tokenBucket(15, 3, null, true))
                .hasMessageContaining("refill-every");
    }

    @Test
    void applicationYaml_bindsTheRealPolicies() throws Exception {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        RateLimitProperties bound = new Binder(ConfigurationPropertySources.from(sources))
                .bind("bidtix.rate-limit", RateLimitProperties.class).get();

        RateLimitProperties.Policy user = bound.policies().get(RateLimitProperties.DEFAULT_USER_POLICY);
        assertThat(user.algorithm()).isEqualTo(RateLimitProperties.Algorithm.TOKEN_BUCKET);
        assertThat(user.capacity()).isEqualTo(15);
        assertThat(user.refillTokens()).isEqualTo(3);
        assertThat(user.refillEvery()).isEqualTo(Duration.ofSeconds(10));
        assertThat(user.failOpen()).isTrue();

        RateLimitProperties.Policy login = bound.policies().get("auth-login");
        assertThat(login.algorithm()).isEqualTo(RateLimitProperties.Algorithm.FIXED_WINDOW);
        assertThat(login.limit()).isEqualTo(5);
        assertThat(login.window()).isEqualTo(Duration.ofMinutes(1));
        assertThat(login.failOpen()).isFalse();
    }

    @Test
    void unknownPolicy_isABug_andFailsLoudly() {
        assertThatThrownBy(() -> service(true).check("typo", "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("typo");
    }

    @Test
    void enabledFlag_comesFromTheProperties() {
        assertThat(service(false).enabled()).isFalse();
        assertThat(service(true).enabled()).isTrue();
    }
}
