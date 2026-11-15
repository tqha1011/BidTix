package com.bidtix.config.ratelimit;

import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real application, a real Redis: counters are shared and atomic, the HTTP answer is a 429 in the usual error
 * format, and the limits are per IP / per user. The shared Redis is never emptied, so every test uses its own
 * IP address or user.
 */
@TestPropertySource(properties = {
        "bidtix.rate-limit.policies.default-user.capacity=3",   // 3 tokens instead of 15: keeps the HTTP test short
        // policies that only exist for the tests
        "bidtix.rate-limit.policies.test-short.limit=2",
        "bidtix.rate-limit.policies.test-short.window=1s",
        "bidtix.rate-limit.policies.test-short.fail-open=true",
        // the real default-user shape: a bucket of 15, 3 more every 10 s (too slow to refill during a test)
        "bidtix.rate-limit.policies.test-burst.algorithm=token-bucket",
        "bidtix.rate-limit.policies.test-burst.capacity=15",
        "bidtix.rate-limit.policies.test-burst.refill-tokens=3",
        "bidtix.rate-limit.policies.test-burst.refill-every=10s",
        // a small, fast bucket to watch the refill: 5 tokens, 2 more every 2 s
        "bidtix.rate-limit.policies.test-refill.algorithm=token-bucket",
        "bidtix.rate-limit.policies.test-refill.capacity=5",
        "bidtix.rate-limit.policies.test-refill.refill-tokens=2",
        "bidtix.rate-limit.policies.test-refill.refill-every=2s"})
class RateLimitIntegrationTest extends PostgresIntegrationTest {

    private static final String WRONG_LOGIN = "{\"email\":\"nobody@test.dev\",\"password\":\"wrong-password\"}";

    @Autowired WebApplicationContext context;
    @Autowired RateLimitService rateLimitService;
    @Autowired StringRedisTemplate redis;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void parallelRequests_exactlyTheLimitGetsThrough() throws Exception {
        String subject = "ip:test-" + UUID.randomUUID();
        int requests = 20;     // auth-login allows 5 per window

        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return rateLimitService.check("auth-login", subject).allowed();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed).as("allowed out of " + requests).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void whenTheWindowEnds_theCountStartsAgain_andBlockedRequestsDidNotExtendIt() throws Exception {
        String subject = "ip:test-" + UUID.randomUUID();

        assertThat(rateLimitService.check("test-short", subject).allowed()).isTrue();
        assertThat(rateLimitService.check("test-short", subject).allowed()).isTrue();
        RateLimitService.Decision blocked = rateLimitService.check("test-short", subject);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isBetween(1L, 1L);

        // hammering while blocked must not push the end of the window away: a second after the first request it is over
        for (int i = 0; i < 20; i++) {
            rateLimitService.check("test-short", subject);
        }
        await().atMost(Duration.ofSeconds(3)).until(() -> rateLimitService.check("test-short", subject).allowed());
    }

    @Test
    void tokenBucket_parallelRequests_exactlyTheCapacityGetsThrough() throws Exception {
        String subject = "user:test-" + UUID.randomUUID();
        int requests = 40;     // the bucket holds 15

        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return rateLimitService.check("test-burst", subject).allowed();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed).as("allowed out of " + requests).isEqualTo(15);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tokenBucket_countsDown_thenBlocks_andSaysWhenTheNextTokenComes() {
        String subject = "user:test-" + UUID.randomUUID();

        for (int token = 14; token >= 0; token--) {
            RateLimitService.Decision decision = rateLimitService.check("test-burst", subject);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remaining()).isEqualTo(token);
            assertThat(decision.limit()).isEqualTo(15);
        }

        RateLimitService.Decision blocked = rateLimitService.check("test-burst", subject);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.remaining()).isZero();
        assertThat(blocked.retryAfterSeconds()).as("the next token comes within one refill period (10 s)")
                .isBetween(1L, 10L);
    }

    @Test
    void tokenBucket_refillsOverTime_byTheConfiguredAmount_notMore() throws Exception {
        String subject = "user:test-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            assertThat(rateLimitService.check("test-refill", subject).allowed()).isTrue();
        }
        assertThat(rateLimitService.check("test-refill", subject).allowed()).as("empty").isFalse();

        Thread.sleep(2_500);     // one 2 s refill period has passed: +2 tokens

        assertThat(rateLimitService.check("test-refill", subject).allowed()).isTrue();
        assertThat(rateLimitService.check("test-refill", subject).allowed()).isTrue();
        assertThat(rateLimitService.check("test-refill", subject).allowed()).as("only 2 were added").isFalse();
    }

    /**
     * The bucket's state is written into Redis directly, so the test does not depend on how long the key lives: a
     * bucket that is about to be full is deleted by Redis, and then a new full bucket starts, which would hide the
     * ceiling. Here: 4 tokens, last refill 2 periods (4 s) ago, so 4 + 2 * 2 = 8 would be added without a ceiling.
     */
    @Test
    void tokenBucket_neverFillsAboveTheCapacity() {
        String subject = "user:test-" + UUID.randomUUID();
        String key = "bidtix-backend:ratelimit:test-refill:" + subject;     // capacity 5, +2 tokens every 2 s
        redis.opsForHash().put(key, "tokens", "4");
        redis.opsForHash().put(key, "ts", String.valueOf(System.currentTimeMillis() - 4_000));

        RateLimitService.Decision decision = rateLimitService.check("test-refill", subject);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).as("the bucket is capped at 5, and one token was taken").isEqualTo(4);
    }

    @Test
    void login_sixthAttemptFromOneIp_is429_inTheUsualErrorFormat() throws Exception {
        String ip = uniqueIp();

        for (int attempt = 1; attempt <= 5; attempt++) {
            mvc.perform(post("/api/v1/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON)
                            .content(WRONG_LOGIN))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("X-RateLimit-Limit", "5"))
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(5 - attempt)));
        }

        mvc.perform(post("/api/v1/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON)
                        .content(WRONG_LOGIN))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"));
    }

    @Test
    void login_anotherIp_hasItsOwnCounter() throws Exception {
        String busy = uniqueIp();
        for (int attempt = 1; attempt <= 6; attempt++) {
            mvc.perform(post("/api/v1/auth/login").with(from(busy)).contentType(MediaType.APPLICATION_JSON)
                    .content(WRONG_LOGIN));
        }

        mvc.perform(post("/api/v1/auth/login").with(from(uniqueIp())).contentType(MediaType.APPLICATION_JSON)
                        .content(WRONG_LOGIN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void register_hasItsOwnPolicy_threePerWindow() throws Exception {
        String ip = uniqueIp();
        String body = "{\"email\":\"a@test.dev\",\"password\":\"short\",\"fullName\":\"A\"}";   // invalid body: 400, still counted

        for (int attempt = 1; attempt <= 3; attempt++) {
            mvc.perform(post("/api/v1/auth/register").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());   // the body is invalid, but the attempt is counted
        }
        mvc.perform(post("/api/v1/auth/register").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void loggedInUser_isLimitedByTheDefaultPolicy_perUser() throws Exception {
        String user = UUID.randomUUID().toString();

        for (int request = 1; request <= 3; request++) {
            mvc.perform(get("/api/v1/events").with(jwt().jwt(token -> token.subject(user))))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "3"))
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(3 - request)));
        }
        mvc.perform(get("/api/v1/events").with(jwt().jwt(token -> token.subject(user))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));

        mvc.perform(get("/api/v1/events").with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousRequest_toAnUnannotatedEndpoint_isNotRateLimited_andStill401() throws Exception {
        for (int request = 1; request <= 5; request++) {
            mvc.perform(get("/api/v1/events").with(from(uniqueIp())))
                    .andExpect(status().isUnauthorized());
        }
    }

    private static String uniqueIp() {
        return "10." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
