package com.bidtix.common.idempotency;

import com.bidtix.common.idempotency.infrastructure.IdempotencyCleanupJob;
import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The job that deletes the idempotency keys whose time is up. */
class IdempotencyCleanupJobTest extends PostgresIntegrationTest {

    @Autowired IdempotencyCleanupJob job;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ScheduledTaskHolder scheduledTasks;

    @Test
    void expiredKeysOfAnyStatusAreDeleted_andTheOthersKept() {
        long user = user();
        key(user, "finished", "COMPLETED", -1);
        key(user, "abandoned", "PROCESSING", -60);
        key(user, "still-replayable", "COMPLETED", 1);
        key(user, "still-running", "PROCESSING", 60);

        job.deleteExpiredKeys();

        assertThat(keys(user)).containsExactlyInAnyOrder("still-replayable", "still-running");
    }

    @Test
    void moreExpiredKeysThanOneBatch_areAllDeletedInOneRun() {
        long user = user();
        jdbc.update("insert into idempotency_keys (user_id, idem_key, request_method, request_path, request_hash, "
                + "status, response_status, expires_at) select ?, 'old-' || n, 'POST', '/x', 'hash', 'COMPLETED', 201, "
                + "now() - interval '1 day' from generate_series(1, 2500) n", user);

        job.deleteExpiredKeys();

        assertThat(keys(user)).isEmpty();
    }

    @Test
    void aKeyAnotherTransactionHolds_isSkipped_notWaitedFor() {
        long user = user();
        key(user, "held", "PROCESSING", -1);
        key(user, "free", "COMPLETED", -1);

        transactionTemplate.executeWithoutResult(tx -> {
            jdbc.queryForObject("select id from idempotency_keys where user_id = ? and idem_key = 'held' for update",
                    Long.class, user);
            // on another connection, while this one holds the row: it must finish without waiting for us
            CompletableFuture.runAsync(job::deleteExpiredKeys).orTimeout(10, TimeUnit.SECONDS).join();
        });

        assertThat(keys(user)).containsExactly("held");
    }

    @Test
    void itRunsOnASchedule_butNotInTheTests() throws NoSuchMethodException {
        assertThat(IdempotencyCleanupJob.class.getMethod("deleteExpiredKeys").getAnnotation(Scheduled.class))
                .isNotNull()
                .extracting(Scheduled::cron).isEqualTo("${bidtix.idempotency.cleanup-cron:45 * * * * *}");
        assertThat(scheduledTasks.getScheduledTasks())
                .noneMatch(task -> task.getTask() instanceof CronTask cron
                        && cron.toString().contains("IdempotencyCleanupJob"));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private long user() {
        return jdbc.queryForObject("insert into users (email, password_hash, full_name, role) "
                + "values (?, 'hash', 'Caller', 'CUSTOMER') returning id", Long.class,
                "cleanup-" + UUID.randomUUID() + "@test.dev");
    }

    /** A key that expires this many minutes from now (negative: already has). */
    private void key(long user, String key, String status, int expiresInMinutes) {
        jdbc.update("insert into idempotency_keys (user_id, idem_key, request_method, request_path, request_hash, "
                        + "status, response_status, expires_at) "
                        + "values (?, ?, 'POST', '/x', 'hash', ?, ?, now() + make_interval(mins => ?))",
                user, key, status, status.equals("COMPLETED") ? 201 : null, expiresInMinutes);
    }

    private List<String> keys(long user) {
        return jdbc.queryForList("select idem_key from idempotency_keys where user_id = ?", String.class, user);
    }
}
