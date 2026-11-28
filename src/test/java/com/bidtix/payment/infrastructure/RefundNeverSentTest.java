package com.bidtix.payment.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import static com.bidtix.support.Counters.jobFailures;
import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.bidtix.payment.infrastructure.RefundFixtures.refundPending;
import static com.bidtix.payment.infrastructure.RefundFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * A refund request that certainly never reached VNPAY (here: nothing listens on the port) gives up its claim and is tried
 * again later, a limited number of times.
 */
@TestPropertySource(properties = {
        "bidtix.payment.vnpay.tmn-code=TESTCODE",
        "bidtix.payment.vnpay.hash-secret=TESTSECRET",
        "bidtix.payment.vnpay.refund-url=http://127.0.0.1:1/refund"})   // connection refused
class RefundNeverSentTest extends PostgresIntegrationTest {

    @Autowired RefundPaymentJob job;
    @Autowired MeterRegistry meters;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aRefusedConnectionGivesUpTheClaim_andSchedulesAnotherTry() {
        String payment = refundPending(jdbc);

        double failuresBefore = jobFailures(meters, "refund");

        job.run();

        assertThat(jobFailures(meters, "refund")).as("the failed item is counted").isGreaterThan(failuresBefore);

        Map<String, Object> row = row(jdbc, payment);
        assertThat(row).containsEntry("status", "REFUND_PENDING").containsEntry("claimed", false)
                .containsEntry("refund_attempts", 1);
        assertThat((Integer) row.get("seconds_to_next")).isBetween(50, 60);
        assertThat((String) row.get("failure_reason")).startsWith("The order expired or was cancelled").contains("[refund]");
    }

    @Test
    void itIsNotTriedBeforeItsTime_andTheWaitGrows() {
        String payment = refundPending(jdbc);
        job.run();

        job.run();   // too early

        assertThat(row(jdbc, payment)).containsEntry("refund_attempts", 1);

        List<Integer> waits = new ArrayList<>();
        for (int attempt = 2; attempt <= 4; attempt++) {
            due(payment);
            job.run();
            assertThat(row(jdbc, payment)).containsEntry("refund_attempts", attempt).containsEntry("claimed", false);
            waits.add((Integer) row(jdbc, payment).get("seconds_to_next"));
        }
        assertThat(waits.get(0)).as("5 minutes").isBetween(290, 300);
        assertThat(waits.get(1)).as("30 minutes").isBetween(1790, 1800);
        assertThat(waits.get(2)).as("30 minutes").isBetween(1790, 1800);
        assertThat(((String) row(jdbc, payment).get("failure_reason")).split("\\[refund]", -1))
                .as("the latest problem replaces the one before").hasSize(2);
    }

    @Test
    void afterTheFifthTry_itKeepsTheClaimForAPersonToLookAt() {
        String payment = refundPending(jdbc);
        jdbc.update("update payments set refund_attempts = 4 where txn_ref = ?", payment);

        double failuresBefore = jobFailures(meters, "refund");

        job.run();

        assertThat(jobFailures(meters, "refund")).as("the failed item is counted").isGreaterThan(failuresBefore);

        assertThat(row(jdbc, payment)).containsEntry("claimed", true).containsEntry("refund_attempts", 5);
        assertThat((String) row(jdbc, payment).get("failure_reason")).contains("gave up after 5 attempts");
    }

    @Test
    void aFullBatchOfFailures_doesNotKeepTheRunGoing() {
        List<String> payments = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            payments.add(refundPending(jdbc));
        }

        assertTimeoutPreemptively(Duration.ofSeconds(60), job::run);

        assertThat(payments).allSatisfy(payment -> assertThat(row(jdbc, payment)).containsEntry("refund_attempts", 1));
    }

    @Test
    void onlyAFailureToConnectCountsAsNeverSent() {
        assertThat(RefundPaymentJob.neverSent(new ResourceAccessException("x", new ConnectException()))).isTrue();
        assertThat(RefundPaymentJob.neverSent(new ResourceAccessException("x", new HttpConnectTimeoutException("x")))).isTrue();

        // the request may have arrived
        assertThat(RefundPaymentJob.neverSent(new ResourceAccessException("x", new HttpTimeoutException("read")))).isFalse();
        assertThat(RefundPaymentJob.neverSent(new ResourceAccessException("x", new IOException("reset")))).isFalse();
        assertThat(RefundPaymentJob.neverSent(new IllegalStateException())).isFalse();
    }

    /** Its waiting time is over. */
    private void due(String payment) {
        jdbc.update("update payments set refund_next_attempt_at = now() - interval '1 second' where txn_ref = ?", payment);
    }
}
