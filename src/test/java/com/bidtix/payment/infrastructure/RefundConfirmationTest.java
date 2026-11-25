package com.bidtix.payment.infrastructure;

import com.bidtix.support.PostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.bidtix.payment.infrastructure.RefundFixtures.refundPending;
import static com.bidtix.payment.infrastructure.RefundFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding out whether a requested refund was made: querydr right after VNPAY accepted the request, and again later for
 * those still waiting. A stand-in for VNPAY answers each reference as the test says.
 */
@TestPropertySource(properties = {
        "bidtix.payment.vnpay.tmn-code=TESTCODE",
        "bidtix.payment.vnpay.hash-secret=TESTSECRET"})
class RefundConfirmationTest extends PostgresIntegrationTest {

    /** Reference to the refund answer's transaction status ("500": a server error). */
    private static final Map<String, String> REFUND = new ConcurrentHashMap<>();
    /** Reference to querydr's "type/status" ("500": a server error). */
    private static final Map<String, String> QUERY = new ConcurrentHashMap<>();
    /** "command reference" of every request received. */
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static final HttpServer VNPAY = startStandIn();

    @DynamicPropertySource
    static void url(DynamicPropertyRegistry registry) {
        registry.add("bidtix.payment.vnpay.refund-url",
                () -> "http://127.0.0.1:" + VNPAY.getAddress().getPort() + "/api");
    }

    @Autowired RefundPaymentJob job;
    @Autowired JdbcTemplate jdbc;

    // ---- right after the request -----------------------------------------------------------------

    @Test
    void anAcceptedRefundThatIsAlreadyDone_isRefundedInTheSameRun() {
        String payment = payment("05", "02/00");

        job.run();

        assertThat(row(jdbc, payment)).containsEntry("status", "REFUNDED");
        assertThat(sent("refund", payment)).isEqualTo(1);
        assertThat(sent("querydr", payment)).isEqualTo(1);
    }

    @Test
    void aFailingQuery_changesNothing_andNeverSendsTheRefundAgain() {
        String payment = payment("05", "500");

        job.run();
        job.run();

        assertThat(row(jdbc, payment)).containsEntry("status", "REFUND_PENDING").containsEntry("claimed", true)
                .containsEntry("refund_attempts", 1).containsEntry("failure_reason", "The order expired or was cancelled");
        assertThat(sent("refund", payment)).isEqualTo(1);
    }

    @Test
    void aRefundWithAnUnknownOutcome_isSettledByALaterQuery() {
        String payment = payment("500", "02/00");
        job.run();
        assertThat(sent("querydr", payment)).as("no answer to confirm").isZero();

        job.confirmRefunds();
        assertThat(sent("querydr", payment)).as("the request was sent a moment ago").isZero();

        requestedMinutesAgo(payment, 2);
        job.confirmRefunds();

        assertThat(row(jdbc, payment)).containsEntry("status", "REFUNDED");
        assertThat(sent("refund", payment)).isEqualTo(1);
    }

    @Test
    void aRefundStillBeingProcessed_isSettledByALaterQuery() {
        String payment = payment("05", "02/05");
        job.run();
        assertThat(row(jdbc, payment)).containsEntry("status", "REFUND_PENDING");

        QUERY.put(payment, "02/00");
        requestedMinutesAgo(payment, 2);
        job.confirmRefunds();

        assertThat(row(jdbc, payment)).containsEntry("status", "REFUNDED");
    }

    // ---- asking again ----------------------------------------------------------------------------

    @Test
    void theWaitBetweenQueriesGrows_andTheyStopAfterTen() {
        String payment = waiting("02/05");
        List<Integer> waits = new ArrayList<>();

        for (int query = 1; query <= 10; query++) {
            due(payment);
            job.confirmRefunds();
            assertThat(queries(payment)).isEqualTo(query);
            waits.add(secondsToNextQuery(payment));
        }
        due(payment);
        job.confirmRefunds();

        assertThat(sent("querydr", payment)).isEqualTo(10);
        int[] expected = {60, 300, 1800, 7200, 43200, 43200, 43200, 43200, 43200, 43200};
        for (int i = 0; i < expected.length; i++) {
            assertThat(waits.get(i)).as("after query " + (i + 1)).isBetween(expected[i] - 10, expected[i]);
        }
    }

    @Test
    void aRefundIsNotQueriedBeforeItsTime_orWhenItWasNeverRequested() {
        String early = waiting("02/00");
        jdbc.update("update payments set refund_next_query_at = now() + interval '1 minute' where txn_ref = ?", early);
        String unrequested = payment("05", "02/00");

        job.confirmRefunds();

        assertThat(sent("querydr", early)).isZero();
        assertThat(sent("querydr", unrequested)).isZero();
    }

    @Test
    void aRejectedRefund_isNotAskedAboutAgain() {
        String payment = waiting("02/09");
        job.confirmRefunds();

        due(payment);
        job.confirmRefunds();

        assertThat(sent("querydr", payment)).isEqualTo(1);
        assertThat((String) row(jdbc, payment).get("failure_reason")).contains("rejected");
    }

    @Test
    void oneFailingQuery_doesNotStopTheOthers() {
        String broken = waiting("500");
        String fine = waiting("02/00");

        job.confirmRefunds();

        assertThat(row(jdbc, broken)).containsEntry("status", "REFUND_PENDING");
        assertThat(row(jdbc, fine)).containsEntry("status", "REFUNDED");
    }

    @Test
    void parallelRuns_askOnce() throws Exception {
        String payment = waiting("02/05");
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        try {
            CountDownLatch ready = new CountDownLatch(runs);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    job.confirmRefunds();
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> result : results) {
                result.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(sent("querydr", payment)).isEqualTo(1);
        assertThat(queries(payment)).isEqualTo(1);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** A payment waiting to be requested, and how the stand-in answers its refund and its query. */
    private String payment(String refundStatus, String query) {
        String txnRef = refundPending(jdbc);
        REFUND.put(txnRef, refundStatus);
        QUERY.put(txnRef, query);
        return txnRef;
    }

    /** A payment whose refund was requested a while ago. */
    private String waiting(String query) {
        String txnRef = payment("05", query);
        requestedMinutesAgo(txnRef, 2);
        return txnRef;
    }

    private void requestedMinutesAgo(String txnRef, int minutes) {
        jdbc.update("update payments set refund_requested_at = now() - make_interval(mins => ?), "
                + "refund_next_query_at = null where txn_ref = ?", minutes, txnRef);
    }

    private void due(String txnRef) {
        jdbc.update("update payments set refund_next_query_at = now() - interval '1 second' where txn_ref = ?", txnRef);
    }

    private int queries(String txnRef) {
        return jdbc.queryForObject("select refund_queries from payments where txn_ref = ?", Integer.class, txnRef);
    }

    private int secondsToNextQuery(String txnRef) {
        return jdbc.queryForObject("select extract(epoch from refund_next_query_at - now())::int from payments where txn_ref = ?",
                Integer.class, txnRef);
    }

    private static long sent(String command, String txnRef) {
        return REQUESTS.stream().filter((command + " " + txnRef)::equals).count();
    }

    @SuppressWarnings("unchecked")
    private static HttpServer startStandIn() {
        try {
            JsonMapper json = JsonMapper.builder().build();
            VnpaySigner signer = new VnpaySigner(new VnpayProperties("TESTCODE", "TESTSECRET", "", "", "", "", "2.1.0",
                    "pay", "refund", "querydr", 15));
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api", exchange -> {
                Map<String, String> request = json.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
                String command = request.get("vnp_Command");
                String txnRef = request.get("vnp_TxnRef");
                REQUESTS.add(command + " " + txnRef);
                boolean query = command.equals("querydr");
                // other tests' payments: a payment record, which settles nothing
                String plan = query ? QUERY.getOrDefault(txnRef, "01/00") : REFUND.getOrDefault(txnRef, "05");
                if (plan.equals("500")) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
                Map<String, String> response = new HashMap<>(Map.of("vnp_ResponseId", UUID.randomUUID().toString(),
                        "vnp_Command", command, "vnp_ResponseCode", "00", "vnp_Message", "ok", "vnp_TmnCode", "TESTCODE",
                        "vnp_TxnRef", txnRef));
                response.put("vnp_TransactionType", query ? plan.split("/")[0] : "02");
                response.put("vnp_TransactionStatus", query ? plan.split("/")[1] : plan);
                List<String> order = new ArrayList<>(List.of("vnp_ResponseId", "vnp_Command", "vnp_ResponseCode",
                        "vnp_Message", "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate",
                        "vnp_TransactionNo", "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo"));
                if (query) {
                    order.addAll(List.of("vnp_PromotionCode", "vnp_PromotionAmount"));
                }
                response.put("vnp_SecureHash", signer.hash(String.join("|",
                        order.stream().map(field -> response.getOrDefault(field, "")).toList())));
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
}
