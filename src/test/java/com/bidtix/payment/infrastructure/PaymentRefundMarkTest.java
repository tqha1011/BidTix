package com.bidtix.payment.infrastructure;

import com.bidtix.payment.domain.Payment;
import com.bidtix.payment.domain.PaymentStatus;
import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Marking a refund as requested: whoever marks a payment is the only one who may ask the provider for its refund. */
class PaymentRefundMarkTest extends PostgresIntegrationTest {

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired PaymentRepository payments;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aPaymentWaitingForARefundIsMarkedOnce() {
        long payment = payment("REFUND_PENDING");

        assertThat(mark(payment, now)).isEqualTo(1);
        assertThat(mark(payment, now.plusSeconds(60))).as("already taken").isZero();

        assertThat(requestedAt(payment)).as("the first mark stays").isEqualTo(now);
        assertThat(jdbc.queryForMap("select status, version from payments where id = ?", payment))
                .containsEntry("status", "REFUND_PENDING").containsEntry("version", 1L);
        assertThat(jdbc.queryForObject("select refund_attempts from payments where id = ?", Integer.class, payment))
                .as("one try counted").isEqualTo(1);
    }

    @Test
    void aPaymentThatDoesNotWaitForARefundCannotBeMarked() {
        for (String status : List.of("PENDING", "SUCCEEDED", "FAILED", "REFUNDED")) {
            long payment = payment(status);

            assertThat(mark(payment, now)).as(status).isZero();

            assertThat(requestedAt(payment)).as(status).isNull();
        }
    }

    @Test
    void markingOnePaymentInParallel_oneCallerGetsIt() throws Exception {
        long payment = payment("REFUND_PENDING");
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            CountDownLatch ready = new CountDownLatch(callers);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mark(payment, now);
                }));
            }
            ready.await();
            go.countDown();
            int marked = 0;
            for (Future<Integer> result : results) {
                marked += result.get(30, TimeUnit.SECONDS);
            }
            assertThat(marked).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- finding the refunds to request ----------------------------------------------------------

    @Test
    void onlyRefundsStillToBeRequestedAreFound_oldestFirst() {
        long newer = refundable("2000-01-02");
        long older = refundable("2000-01-01");
        long marked = refundable("2000-01-01");
        jdbc.update("update payments set refund_requested_at = now() where id = ?", marked);
        long withoutRequestTime = payment("REFUND_PENDING");   // from before requested_at was stored
        long succeeded = refundable("2000-01-01");
        jdbc.update("update payments set status = 'SUCCEEDED' where id = ?", succeeded);

        assertThat(find(1000)).containsSubsequence(older, newer)
                .doesNotContain(marked, withoutRequestTime, succeeded);
        assertThat(find(1)).hasSize(1);
    }

    @Test
    void aRefundWaitingForItsNextTryIsNotFoundBeforeThen() {
        long later = refundable("2000-01-01");
        long due = refundable("2000-01-01");
        jdbc.update("update payments set refund_next_attempt_at = now() + interval '1 minute' where id = ?", later);
        jdbc.update("update payments set refund_next_attempt_at = now() - interval '1 second' where id = ?", due);

        assertThat(find(1000)).contains(due).doesNotContain(later);
    }

    @Test
    void aRefundLockedByAnotherTransactionIsSkipped_notWaitedFor() throws Exception {
        long payment = refundable("2000-01-01");
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from payments where id = ? for update", Long.class, payment);
                try {
                    assertThat(other.submit(() -> find(1000)).get(10, TimeUnit.SECONDS)).doesNotContain(payment);
                } catch (Exception e) {
                    throw new AssertionError("the query waited for the lock", e);
                }
            });
        } finally {
            other.shutdownNow();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private List<Long> find(int limit) {
        return tx.execute(status -> payments.findPaymentWithStatus(PaymentStatus.REFUND_PENDING, Instant.now(), limit)
                .stream()
                .map(Payment::getId)
                .toList());
    }

    /** A REFUND_PENDING payment with its request time stored, created on this day. */
    private long refundable(String createdOn) {
        long id = payment("REFUND_PENDING");
        jdbc.update("update payments set requested_at = now(), created_at = ?::timestamptz where id = ?", createdOn, id);
        return id;
    }

    private int mark(long payment, Instant at) {
        return tx.execute(status -> payments.markRefundRequested(payment, at));
    }

    private Instant requestedAt(long payment) {
        Timestamp stored = jdbc.queryForObject("select refund_requested_at from payments where id = ?", Timestamp.class, payment);
        return stored == null ? null : stored.toInstant();
    }

    /** A payment in this status, on an order of its own. */
    private long payment(String status) {
        String run = UUID.randomUUID().toString().replace("-", "");
        long user = jdbc.queryForObject("insert into users (email, password_hash, full_name, role) "
                + "values (?, 'hash', 'Payer', 'CUSTOMER') returning id", Long.class, "refund-" + run + "@test.dev");
        long venue = jdbc.queryForObject("insert into venues (name, address, city) values ('Arena', '1 Street', 'Hanoi') "
                + "returning id", Long.class);
        long event = jdbc.queryForObject("insert into events (organizer_id, venue_id, title, starts_at, ends_at, "
                + "sale_starts_at, sale_ends_at) values (?, ?, 'Concert', now() + interval '30 days', "
                + "now() + interval '31 days', now(), now() + interval '29 days') returning id", Long.class, user, venue);
        long order = jdbc.queryForObject("insert into orders (user_id, event_id, source, status, total_amount, expires_at, "
                        + "order_code) values (?, ?, 'BOOKING', 'CANCELLED', 1000, now(), ?) returning id", Long.class,
                user, event, "ORDER_" + run.substring(0, 13).toUpperCase());
        return jdbc.queryForObject("insert into payments (order_id, provider, txn_ref, amount, status) "
                + "values (?, 'VNPAY', ?, 1000, ?) returning id", Long.class, order, run, status);
    }
}
