package com.bidtix.payment.infrastructure;

import com.bidtix.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.bidtix.payment.infrastructure.RefundFixtures.refundPending;
import static org.assertj.core.api.Assertions.assertThat;

/** The database is shared, so every count is compared with what it was before the test added its rows. */
class PaymentBacklogGaugesTest extends PostgresIntegrationTest {

    @Autowired PaymentBacklogGauges gauges;
    @Autowired MeterRegistry meters;
    @Autowired JdbcTemplate jdbc;

    private final List<String> payments = new ArrayList<>();
    private final List<String> events = new ArrayList<>();

    /** Other tests run the refund job over every waiting payment: leave none behind. */
    @AfterEach
    void cleanUp() {
        payments.forEach(ref -> jdbc.update("update payments set status = 'REFUNDED' where txn_ref = ?", ref));
        events.forEach(id -> jdbc.update("update payment_webhook_events set processed_at = now() "
                + "where provider_event_id = ?", id));
    }

    @Test
    void refundsWaitingAreCounted() {
        double before = read("bidtix.payments.refund.pending");

        waitingRefund();
        waitingRefund();

        assertThat(read("bidtix.payments.refund.pending")).isEqualTo(before + 2);
    }

    @Test
    void theOldestWaitingRefundGivesTheAge() {
        String old = waitingRefund();
        waitingRefund();
        jdbc.update("update payments set created_at = now() - interval '400 days' where txn_ref = ?", old);

        assertThat(read("bidtix.payments.refund.oldest.age.seconds"))
                .isBetween((double) Duration.ofDays(400).toSeconds(), Duration.ofDays(400).toSeconds() + 300.0);
    }

    @Test
    void refundsTheJobHasGivenUpOnAreCounted_andOnesStillBeingTriedAreNot() {
        double before = read("bidtix.payments.refund.stuck");

        waitingRefund();                                                   // not tried yet
        change(waitingRefund(), "refund_attempts = 4, refund_queries = 9");   // still being tried
        change(waitingRefund(), "refund_attempts = 5");                    // no more requests
        change(waitingRefund(), "refund_queries = 10");                    // no more queries
        change(waitingRefund(), "requested_at = null");                    // VNPAY cannot refund it without this

        assertThat(read("bidtix.payments.refund.stuck")).isEqualTo(before + 3);
    }

    @Test
    void paymentsLeftPendingFor30MinutesAreCounted() {
        double before = read("bidtix.payments.pending.stale");

        change(waitingRefund(), "status = 'PENDING', created_at = now() - interval '31 minutes'");
        change(waitingRefund(), "status = 'PENDING', created_at = now() - interval '29 minutes'");

        assertThat(read("bidtix.payments.pending.stale")).isEqualTo(before + 1);
    }

    @Test
    void callbacksUnprocessedFor5MinutesAreCounted() {
        double before = read("bidtix.payments.webhooks.unprocessed");

        callback("6 minutes", false);
        callback("4 minutes", false);
        callback("6 minutes", true);

        assertThat(read("bidtix.payments.webhooks.unprocessed")).isEqualTo(before + 1);
    }

    private String waitingRefund() {
        String ref = refundPending(jdbc);
        payments.add(ref);
        return ref;
    }

    private void change(String txnRef, String assignments) {
        jdbc.update("update payments set " + assignments + " where txn_ref = ?", txnRef);
    }

    private void callback(String ago, boolean processed) {
        String id = "gauge-" + UUID.randomUUID();
        events.add(id);
        jdbc.update("insert into payment_webhook_events (provider, provider_event_id, payload, created_at, processed_at) "
                + "values ('VNPAY', ?, '{}'::jsonb, now() - cast(? as interval), case when ? then now() end)",
                id, ago, processed);
    }

    private double read(String gauge) {
        gauges.refresh();
        return meters.get(gauge).gauge().value();
    }
}
