package com.bidtix.payment.infrastructure;

import com.bidtix.support.PostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** One run of the refund job against a stand-in for VNPAY that refuses every refund (response code 91). */
@TestPropertySource(properties = {
        "bidtix.payment.vnpay.tmn-code=TESTCODE",
        "bidtix.payment.vnpay.hash-secret=TESTSECRET"})
class RefundPaymentJobRunTest extends PostgresIntegrationTest {

    /** The request bodies the stand-in received. */
    private static final List<Map<String, String>> REQUESTS = new CopyOnWriteArrayList<>();
    /** How to answer a reference: "500", "hang-up" or "unsigned-99"; otherwise a signed refusal. */
    private static final Map<String, String> ANSWERS = new ConcurrentHashMap<>();
    private static final HttpServer VNPAY = startStandIn();

    @DynamicPropertySource
    static void refundUrl(DynamicPropertyRegistry registry) {
        registry.add("bidtix.payment.vnpay.refund-url",
                () -> "http://127.0.0.1:" + VNPAY.getAddress().getPort() + "/refund");
    }

    @Autowired RefundPaymentJob job;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScheduledTaskHolder scheduledTasks;

    @Test
    void aWaitingRefundIsClaimedAndRequestedOnce_andTheAnswerRecorded() {
        String txnRef = refundPending();

        job.run();
        job.run();

        assertThat(requestsFor(txnRef)).singleElement().satisfies(request -> assertThat(request)
                .containsEntry("vnp_Command", "refund").containsEntry("vnp_Amount", "100000")
                .containsKey("vnp_SecureHash"));
        Map<String, Object> payment = jdbc.queryForMap(
                "select status, failure_reason, refund_requested_at from payments where txn_ref = ?", txnRef);
        assertThat(payment).containsEntry("status", "REFUND_PENDING");
        assertThat(payment.get("refund_requested_at")).isNotNull();
        assertThat((String) payment.get("failure_reason")).contains("response code 91");
    }

    @Test
    void onePaymentThatCannotBeRequested_doesNotStopTheOthers() {
        String broken = refundPending();   // older, so it comes first in the batch
        jdbc.update("update payments set amount = ? where txn_ref = ?", 100_000_000_000_000_000L, broken);   // x100 overflows
        String next = refundPending();

        job.run();

        assertThat(requestsFor(broken)).isEmpty();
        assertThat(requestsFor(next)).hasSize(1);
        assertThat(RefundFixtures.row(jdbc, broken)).as("never sent: to be tried again")
                .containsEntry("claimed", false).containsEntry("refund_attempts", 1);
    }

    @Test
    void whenTheOutcomeIsUnknown_theClaimStays_andNothingIsSentAgain() {
        for (String mode : List.of("500", "hang-up", "unsigned-99")) {
            String payment = refundPending();
            ANSWERS.put(payment, mode);

            job.run();
            jdbc.update("update payments set refund_next_attempt_at = null where txn_ref = ?", payment);
            job.run();

            assertThat(requestsFor(payment)).as(mode).hasSize(1);
            assertThat(RefundFixtures.row(jdbc, payment)).as(mode).containsEntry("claimed", true)
                    .containsEntry("status", "REFUND_PENDING");
            assertThat((String) RefundFixtures.row(jdbc, payment).get("failure_reason")).as(mode)
                    .contains(mode.equals("unsigned-99") ? "response code 99" : "outcome unknown");
        }
    }

    @Test
    void theTestsDoNotRunItOnASchedule() {
        // otherwise every test context would claim other tests' payments and call the real VNPAY
        assertThat(scheduledTasks.getScheduledTasks())
                .noneMatch(task -> task.getTask() instanceof CronTask cron
                        && cron.toString().contains("RefundPaymentJob"));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static List<Map<String, String>> requestsFor(String txnRef) {
        return REQUESTS.stream().filter(request -> txnRef.equals(request.get("vnp_TxnRef"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static HttpServer startStandIn() {
        try {
            JsonMapper json = JsonMapper.builder().build();
            VnpayProperties properties = new VnpayProperties("TESTCODE", "TESTSECRET", "", "", "", "", "2.1.0", "pay",
                    "refund", "querydr", 15);
            VnpaySigner signer = new VnpaySigner(properties);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/refund", exchange -> {
                Map<String, String> request = json.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
                REQUESTS.add(request);
                String mode = ANSWERS.getOrDefault(request.get("vnp_TxnRef"), "");
                if (mode.equals("hang-up")) {
                    exchange.close();   // the request arrived, no answer comes back
                    return;
                }
                if (!mode.isEmpty()) {
                    // what the sandbox does: a 500 page, or an error body with no signature
                    byte[] error = "{\"vnp_ResponseCode\":\"99\",\"vnp_Message\":\"Unexpected error\"}".getBytes();
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(mode.equals("500") ? 500 : 200, error.length);
                    exchange.getResponseBody().write(error);
                    exchange.close();
                    return;
                }
                Map<String, String> response = new HashMap<>(Map.of("vnp_ResponseId", "res1", "vnp_Command", "refund",
                        "vnp_ResponseCode", "91", "vnp_Message", "not found", "vnp_TmnCode", "TESTCODE",
                        "vnp_TxnRef", request.get("vnp_TxnRef")));
                // the response's signed values in VNPAY's order; the ones this answer lacks are empty
                response.put("vnp_SecureHash", signer.hash("res1|refund|91|not found|TESTCODE|"
                        + request.get("vnp_TxnRef") + "|||||||"));
                byte[] body = json.writeValueAsBytes(response);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A payment waiting for its refund, on an order of its own; returns its reference. */
    private String refundPending() {
        String run = UUID.randomUUID().toString().replace("-", "");
        long user = jdbc.queryForObject("insert into users (email, password_hash, full_name, role) "
                + "values (?, 'hash', 'Payer', 'CUSTOMER') returning id", Long.class, "run-" + run + "@test.dev");
        long venue = jdbc.queryForObject("insert into venues (name, address, city) values ('Arena', '1 Street', 'Hanoi') "
                + "returning id", Long.class);
        long event = jdbc.queryForObject("insert into events (organizer_id, venue_id, title, starts_at, ends_at, "
                + "sale_starts_at, sale_ends_at) values (?, ?, 'Concert', now() + interval '30 days', "
                + "now() + interval '31 days', now(), now() + interval '29 days') returning id", Long.class, user, venue);
        long order = jdbc.queryForObject("insert into orders (user_id, event_id, source, status, total_amount, expires_at, "
                        + "order_code) values (?, ?, 'BOOKING', 'CANCELLED', 1000, now(), ?) returning id", Long.class,
                user, event, "ORDER_" + run.substring(0, 13).toUpperCase());
        jdbc.update("insert into payments (order_id, provider, txn_ref, amount, status, requested_at) "
                + "values (?, 'VNPAY', ?, 1000, 'REFUND_PENDING', now())", order, run);
        return run;
    }
}
