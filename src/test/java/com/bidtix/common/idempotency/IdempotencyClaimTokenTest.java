package com.bidtix.common.idempotency;

import com.bidtix.common.idempotency.service.IdempotencyService;
import com.bidtix.common.idempotency.service.IdempotencyService.Claim;
import com.bidtix.common.idempotency.service.IdempotencyService.Outcome;
import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A key can be held by one run of a request at a time, but an earlier run may still be going: it was slow and its key
 * was taken over, or its row was removed and the key claimed again. Such a run must not touch the row of the later one.
 */
class IdempotencyClaimTokenTest extends PostgresIntegrationTest {

    @Autowired IdempotencyService service;
    @Autowired JdbcTemplate jdbc;

    private long user;
    private String key;

    @BeforeEach
    void ownUserAndKey() {
        user = jdbc.queryForObject("insert into users (email, password_hash, full_name, role) "
                        + "values (?, 'hash', 'Caller', 'CUSTOMER') returning id", Long.class,
                "token-" + UUID.randomUUID() + "@test.dev");
        key = UUID.randomUUID().toString();
    }

    @Test
    void takingOverAnAbandonedKey_givesItANewToken() {
        Claim first = claim();
        abandon();

        Claim second = claim();

        assertThat(second.outcome()).isEqualTo(Outcome.PROCEED);
        assertThat(second.token()).isNotNull().isNotEqualTo(first.token());
    }

    @Test
    void anEarlierRunThatFails_doesNotRemoveTheKeyOfTheRunThatTookOver() {
        Claim first = claim();
        abandon();
        claim();

        service.release(user, key, first.token());

        assertThat(claim().outcome()).as("the second run still holds the key").isEqualTo(Outcome.IN_PROGRESS);
    }

    @Test
    void anEarlierRunThatSucceeds_doesNotStoreItsResponseOnTheLaterRunsKey() {
        Claim first = claim();
        abandon();
        Claim second = claim();

        assertThatThrownBy(() -> service.complete(user, key, first.token(), 201, "{\"run\":1}"))
                .isInstanceOf(IllegalStateException.class);
        service.complete(user, key, second.token(), 201, "{\"run\":2}");

        assertThat(claim().responseBody()).contains("2").doesNotContain("1");
    }

    @Test
    void anEarlierRunWithoutABody_doesNotCompleteTheLaterRunsKeyEither() {
        Claim first = claim();
        abandon();
        claim();

        assertThatThrownBy(() -> service.complete(user, key, first.token(), 204, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(row()).containsEntry("status", "PROCESSING");
    }

    @Test
    void aReleasedRun_doesNotRemoveAKeyClaimedAfresh() {
        Claim first = claim();
        jdbc.update("delete from idempotency_keys where user_id = ? and idem_key = ?", user, key);
        Claim second = claim();

        service.release(user, key, first.token());

        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(rows()).hasSize(1);
    }

    @Test
    void aDuplicateThatIsToldToWait_leavesTheRunningTokenAlone() {
        Claim running = claim();

        Claim duplicate = claim();
        service.complete(user, key, running.token(), 201, "{\"run\":1}");

        assertThat(duplicate.outcome()).isEqualTo(Outcome.IN_PROGRESS);
        assertThat(duplicate.token()).isNull();
        assertThat(claim().outcome()).isEqualTo(Outcome.REPLAY);
    }

    private Claim claim() {
        return service.claim(user, key, "POST", "/api/v1/orders", "hash");
    }

    /** As if the run that holds the key had stopped answering an hour ago. */
    private void abandon() {
        jdbc.update("update idempotency_keys set updated_at = now() - interval '1 hour' where user_id = ? "
                + "and idem_key = ?", user, key);
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("select * from idempotency_keys where user_id = ? and idem_key = ?", user, key);
    }

    private Map<String, Object> row() {
        return rows().getFirst();
    }
}
