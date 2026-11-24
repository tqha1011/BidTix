package com.bidtix.payment.infrastructure;

import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What V15 added to {@code payments}: the VNPAY provider, our reference per attempt, the REFUND_PENDING status and
 * "one SUCCEEDED payment per order". Written with plain SQL: these are rules of the database, which stays the last line of defence.
 */
class PaymentSchemaTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void anOrderCanHaveSeveralPayments_oneForEachAttempt() {
        long order = order();

        payment(order, "VNPAY", "ref-" + UUID.randomUUID(), "FAILED");
        payment(order, "VNPAY", "ref-" + UUID.randomUUID(), "PENDING");

        assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, order)).isEqualTo(2);
    }

    @Test
    void theReferenceOfAnAttemptIsUniquePerProvider() {
        long order = order();
        String ref = "ref-" + UUID.randomUUID();
        payment(order, "VNPAY", ref, "PENDING");

        assertThatThrownBy(() -> payment(order(), "VNPAY", ref, "PENDING")).isInstanceOf(DataIntegrityViolationException.class);
        payment(order(), "MOCK", ref, "PENDING");   // another provider may reuse the text
    }

    @Test
    void aReferenceIsRequired() {
        assertThatThrownBy(() -> jdbc.update("insert into payments (order_id, provider, amount) values (?, 'VNPAY', 1000)", order()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUnknownProviderOrStatusIsRefused() {
        long order = order();

        assertThatThrownBy(() -> payment(order, "PAYPAL", "ref-" + UUID.randomUUID(), "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> payment(order, "VNPAY", "ref-" + UUID.randomUUID(), "DONE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anOrderCanBeConfirmedPaidOnlyOnce_aSecondConfirmedPaymentMustBeARefund() {
        long order = order();
        payment(order, "VNPAY", "ref-" + UUID.randomUUID(), "SUCCEEDED");

        assertThatThrownBy(() -> payment(order, "VNPAY", "ref-" + UUID.randomUUID(), "SUCCEEDED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        payment(order, "VNPAY", "ref-" + UUID.randomUUID(), "REFUND_PENDING");
        payment(order, "VNPAY", "ref-" + UUID.randomUUID(), "REFUNDED");
        assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, order)).isEqualTo(3);
    }

    @Test
    void aSucceededPaymentOnOneOrderDoesNotBlockAnotherOrder() {
        payment(order(), "VNPAY", "ref-" + UUID.randomUUID(), "SUCCEEDED");
        payment(order(), "VNPAY", "ref-" + UUID.randomUUID(), "SUCCEEDED");
    }

    @Test
    void theMappedEntityMatchesTheTable() {
        // the application context started with ddl-auto=validate, so a Payment/txn_ref mismatch would have failed there;
        // this checks the column is the one the entity reads and writes
        long order = order();
        String ref = "ref-" + UUID.randomUUID();
        payment(order, "VNPAY", ref, "PENDING");

        assertThat(jdbc.queryForObject("select txn_ref from payments where order_id = ?", String.class, order)).isEqualTo(ref);
    }

    private void payment(long order, String provider, String ref, String status) {
        jdbc.update("insert into payments (order_id, provider, txn_ref, amount, status) values (?, ?, ?, 1000, ?)",
                order, provider, ref, status);
    }

    /** A bare order (with the rows it needs: a user, a venue and an event), inserted by SQL. */
    private long order() {
        String run = UUID.randomUUID().toString().replace("-", "");
        long user = jdbc.queryForObject("insert into users (email, password_hash, full_name, role) "
                + "values (?, 'hash', 'Payer', 'CUSTOMER') returning id", Long.class, "pay-" + run + "@test.dev");
        long venue = jdbc.queryForObject("insert into venues (name, address, city) values ('Arena', '1 Street', 'Hanoi') "
                + "returning id", Long.class);
        long event = jdbc.queryForObject("insert into events (organizer_id, venue_id, title, starts_at, ends_at, "
                + "sale_starts_at, sale_ends_at) values (?, ?, 'Concert', now() + interval '30 days', "
                + "now() + interval '31 days', now(), now() + interval '29 days') returning id", Long.class, user, venue);
        return jdbc.queryForObject("insert into orders (user_id, event_id, source, total_amount, expires_at, order_code) "
                + "values (?, ?, 'BOOKING', 1000, now() + interval '15 minutes', ?) returning id", Long.class,
                user, event, "ORDER_" + run.substring(0, 13));
    }
}
