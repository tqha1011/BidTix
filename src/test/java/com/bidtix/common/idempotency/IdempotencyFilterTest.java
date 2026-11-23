package com.bidtix.common.idempotency;

import com.bidtix.config.exceptions.BusinessException;
import com.jayway.jsonpath.JsonPath;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.support.PostgresIntegrationTest;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The filter on a made-up route that counts how many times it really ran. The same key and body run once and then
 * replay; another body is 422; a failure frees the key; a stuck PROCESSING key is 409 until it is stale; ten
 * duplicates at the same instant run once.
 */
@TestPropertySource(properties = "bidtix.idempotency.paths=/api/v1/test-idempotency/**")
class IdempotencyFilterTest extends PostgresIntegrationTest {

    static final AtomicInteger RUNS = new AtomicInteger();

    @TestConfiguration
    static class Routes {
        @RestController
        static class TestController {
            @PostMapping("/api/v1/test-idempotency/ok")
            Map<String, Object> ok(@RequestBody Map<String, Object> body) {
                return Map.of("run", RUNS.incrementAndGet(), "amount", body.get("amount"));
            }

            @PostMapping("/api/v1/test-idempotency/fail")
            Map<String, Object> fail(@RequestBody Map<String, Object> body) {
                RUNS.incrementAndGet();
                throw new BusinessException(ErrorCode.INVALID_MONEY_BID);
            }
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired IdempotencyFilter filter;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        RUNS.set(0);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
    }

    @Test
    void theSameKeyAndBody_runsOnce_andReplaysTheStoredResponse() throws Exception {
        User user = user();
        String key = key();

        MvcResult first = send(user, key, "ok", "{\"amount\":100}");
        MvcResult second = send(user, key, "ok", "{\"amount\":100}");

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        // jsonb rewrites the spacing of the stored JSON, so compare values, not text
        String replayed = second.getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(replayed, "$.run")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(replayed, "$.amount")).isEqualTo(100);
        assertThat(RUNS).hasValue(1);
        assertThat(status(user, key)).isEqualTo("COMPLETED");
    }

    @Test
    void theSameKeyWithAnotherBody_is422_andDoesNotRun() throws Exception {
        User user = user();
        String key = key();
        send(user, key, "ok", "{\"amount\":100}");

        MvcResult reused = send(user, key, "ok", "{\"amount\":200}");

        assertThat(reused.getResponse().getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(reused.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(RUNS).hasValue(1);
    }

    @Test
    void aMissingOrTooLongKey_is400_andDoesNotRun() throws Exception {
        User user = user();

        MvcResult missing = send(user, null, "ok", "{\"amount\":100}");
        MvcResult tooLong = send(user, "k".repeat(65), "ok", "{\"amount\":100}");

        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(missing.getResponse().getContentAsString()).contains("INVALID_REQUEST");
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        assertThat(RUNS).hasValue(0);
    }

    @Test
    void aFailedRequest_isNotStored_soTheRetryRunsAgain() throws Exception {
        User user = user();
        String key = key();

        MvcResult first = send(user, key, "fail", "{\"amount\":1}");
        assertThat(first.getResponse().getStatus()).isEqualTo(400);
        assertThat(count(user, key)).as("the key is freed after a failure").isZero();

        MvcResult retry = send(user, key, "fail", "{\"amount\":1}");

        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(RUNS).hasValue(2);
    }

    @Test
    void theSameKeyOfTwoUsers_areIndependent() throws Exception {
        String key = key();

        send(user(), key, "ok", "{\"amount\":100}");
        MvcResult other = send(user(), key, "ok", "{\"amount\":100}");

        assertThat(other.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(RUNS).hasValue(2);
    }

    @Test
    void aKeyStillBeingProcessed_is409_untilItIsStale() throws Exception {
        User user = user();
        String key = key();
        // what a request that is still running leaves behind: the same body, PROCESSING
        MvcResult probe = send(user, key, "ok", "{\"amount\":100}");
        jdbc.update("update idempotency_keys set status = 'PROCESSING', response_status = null, response_body = null "
                + "where user_id = ? and idem_key = ?", user.getId(), key);
        assertThat(probe.getResponse().getStatus()).isEqualTo(200);

        MvcResult busy = send(user, key, "ok", "{\"amount\":100}");
        assertThat(busy.getResponse().getStatus()).isEqualTo(409);
        assertThat(busy.getResponse().getContentAsString()).contains("IDEMPOTENCY_REQUEST_IN_PROGRESS");
        assertThat(RUNS).hasValue(1);

        // the server that held it died: after the timeout the key may be used again
        jdbc.update("update idempotency_keys set updated_at = now() - interval '1 hour' where user_id = ? and idem_key = ?",
                user.getId(), key);
        MvcResult taken = send(user, key, "ok", "{\"amount\":100}");

        assertThat(taken.getResponse().getStatus()).isEqualTo(200);
        assertThat(RUNS).hasValue(2);
        assertThat(status(user, key)).isEqualTo("COMPLETED");
    }

    @Test
    void anExpiredKey_startsANewRequest() throws Exception {
        User user = user();
        String key = key();
        send(user, key, "ok", "{\"amount\":100}");
        jdbc.update("update idempotency_keys set expires_at = now() - interval '1 minute' where user_id = ? and idem_key = ?",
                user.getId(), key);

        MvcResult again = send(user, key, "ok", "{\"amount\":100}");

        assertThat(again.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(RUNS).hasValue(2);
    }

    @Test
    void tenDuplicatesAtTheSameInstant_runExactlyOnce() throws Exception {
        User user = user();
        String key = key();
        int n = 10;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MvcResult>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return send(user, key, "ok", "{\"amount\":100}");
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            int originals = 0;
            for (Future<MvcResult> result : results) {
                var response = result.get(30, TimeUnit.SECONDS).getResponse();
                assertThat(response.getStatus()).as("a duplicate is a replay (200) or still running (409)")
                        .isIn(200, 409);
                if (response.getStatus() == 200 && response.getHeader("Idempotent-Replayed") == null) {
                    originals++;
                }
            }
            assertThat(originals).as("one request ran, the rest did not").isEqualTo(1);
            assertThat(RUNS).as("the business code ran once").hasValue(1);
            assertThat(count(user, key)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private MvcResult send(User user, String key, String route, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/test-idempotency/" + route)
                .principal((java.security.Principal) () -> user.getPublicId().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    private User user() {
        return users.save(new User("idem-" + UUID.randomUUID() + "@test.dev", "hash", "Idem", UserRole.CUSTOMER));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private String status(User user, String key) {
        return jdbc.queryForObject("select status from idempotency_keys where user_id = ? and idem_key = ?",
                String.class, user.getId(), key);
    }

    private int count(User user, String key) {
        return jdbc.queryForObject("select count(*) from idempotency_keys where user_id = ? and idem_key = ?",
                Integer.class, user.getId(), key);
    }
}
