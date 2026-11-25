package com.bidtix.payment.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

/** Payments waiting for their refund, inserted by SQL, each on a user, event and order of its own. */
final class RefundFixtures {

    private RefundFixtures() {
    }

    /** A REFUND_PENDING payment of 1000 VND that was never requested; returns its reference. */
    static String refundPending(JdbcTemplate jdbc) {
        String run = UUID.randomUUID().toString().replace("-", "");
        long user = jdbc.queryForObject("insert into users (email, password_hash, full_name, role) "
                + "values (?, 'hash', 'Payer', 'CUSTOMER') returning id", Long.class, "retry-" + run + "@test.dev");
        long venue = jdbc.queryForObject("insert into venues (name, address, city) values ('Arena', '1 Street', 'Hanoi') "
                + "returning id", Long.class);
        long event = jdbc.queryForObject("insert into events (organizer_id, venue_id, title, starts_at, ends_at, "
                + "sale_starts_at, sale_ends_at) values (?, ?, 'Concert', now() + interval '30 days', "
                + "now() + interval '31 days', now(), now() + interval '29 days') returning id", Long.class, user, venue);
        long order = jdbc.queryForObject("insert into orders (user_id, event_id, source, status, total_amount, expires_at, "
                        + "order_code) values (?, ?, 'BOOKING', 'CANCELLED', 1000, now(), ?) returning id", Long.class,
                user, event, "ORDER_" + run.substring(0, 13).toUpperCase());
        jdbc.update("insert into payments (order_id, provider, txn_ref, amount, status, failure_reason, requested_at) "
                + "values (?, 'VNPAY', ?, 1000, 'REFUND_PENDING', 'The order expired or was cancelled', now())", order, run);
        return run;
    }

    static Map<String, Object> row(JdbcTemplate jdbc, String txnRef) {
        return jdbc.queryForMap("select status, failure_reason, refund_attempts, refund_requested_at is not null as claimed, "
                + "extract(epoch from refund_next_attempt_at - now())::int as seconds_to_next from payments where txn_ref = ?",
                txnRef);
    }
}
