package com.bidtix.payment.infrastructure;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.BookingService;
import com.bidtix.booking.service.GeneralAdmissionFixtures;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.payment.service.PaymentService;
import com.bidtix.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** One run of the PENDING payment job against a stand-in for VNPAY's querydr. */
@TestPropertySource(properties = {
        "bidtix.payment.vnpay.tmn-code=TESTCODE",
        "bidtix.payment.vnpay.hash-secret=TESTSECRET"})
class PaymentPendingCancelJobTest extends PostgresIntegrationTest {

    private static final List<Map<String, String>> REQUESTS = new CopyOnWriteArrayList<>();
    /** How to answer a reference; see {@link #answer}. Anything unknown (other tests' payments) is "not found". */
    private static final Map<String, String> ANSWERS = new ConcurrentHashMap<>();
    private static final VnpaySigner STAND_IN_SIGNER = new VnpaySigner(new VnpayProperties("TESTCODE", "TESTSECRET",
            "", "", "", "", "2.1.0", "pay", "refund", "querydr", 15));
    private static final HttpServer VNPAY = startStandIn();

    @DynamicPropertySource
    static void queryUrl(DynamicPropertyRegistry registry) {
        registry.add("bidtix.payment.vnpay.refund-url",
                () -> "http://127.0.0.1:" + VNPAY.getAddress().getPort() + "/transaction");
    }

    @Autowired PaymentPendingCancelJob job;
    @Autowired PaymentRepository payments;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService paymentService;
    @Autowired BookingService bookingService;
    @Autowired EntityManager em;
    @Autowired ScheduledTaskHolder scheduledTasks;

    /** Other tests' payments in the shared database would use up the run's limit: none of them is due. */
    @BeforeEach
    void putOtherPaymentsAside() {
        jdbc.update("update payments set next_status_query_at = now() + interval '1 day' where status = 'PENDING'");
    }

    @Test
    void aPaidPayment_ofAnOrderStillWaiting_issuesTheTickets() {
        Fixture f = GeneralAdmissionFixtures.create(em, transactionTemplate, 10, 1);
        UUID user = f.userIds().get(0);
        String code = bookingService.bookTicket(new BookingTicketRequest(f.eventId(), f.zoneId(), null, 2), user, UUID.randomUUID().toString())
                .orderCode();
        String txnRef = UriComponentsBuilder.fromUriString(paymentService.createUrlPayment(code, user, "203.0.113.7"))
                .build().getQueryParams().getFirst("vnp_TxnRef");
        long amount = jdbc.queryForObject("select total_amount from orders where order_code = ?", Long.class, code);
        jdbc.update("update payments set requested_at = now() - interval '4 minutes' where txn_ref = ?", txnRef);
        ANSWERS.put(txnRef, "paid:" + amount * 100);

        job.cancelPendingPayments();

        assertThat(row(txnRef)).containsEntry("status", "SUCCEEDED");
        assertThat(jdbc.queryForObject("select status from orders where order_code = ?", String.class, code))
                .isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select count(*) from tickets t join orders o on o.id = t.order_id "
                + "where o.order_code = ?", Integer.class, code)).isEqualTo(2);
    }

    @Test
    void aPaymentJustMade_isLeftToTheCallback() {
        String txnRef = pending(2);

        job.cancelPendingPayments();

        assertThat(requestsFor(txnRef)).isEmpty();
    }

    @Test
    void whenVnpayCannotBeReached_theRunStops_andTheOthersWaitUntouched() {
        String older = pending(10);
        String newest = pending(5);   // asked first
        ANSWERS.put(newest, "hang-up");

        job.cancelPendingPayments();

        assertThat(requestsFor(older)).isEmpty();
        assertThat(row(older)).containsEntry("status_queries", 0);

        job.cancelPendingPayments();

        assertThat(requestsFor(older)).hasSize(1);
    }

    @Test
    void theNextQueryIsPlannedFromTheTimeThePaymentWasMade() {
        String txnRef = pending(4);

        job.cancelPendingPayments();

        // the second query belongs 10 minutes after the payment: 6 minutes from now
        assertThat((Integer) row(txnRef).get("seconds_to_next")).isBetween(5 * 60, 6 * 60 + 5);
    }

    /**
     * The page closes 1 to 15 minutes after a payment is made (the order's deadline may come first), and the stock stays
     * 15 minutes more. Whatever the case, the job asks at least twice in between, also when it runs late within its
     * minute.
     */
    @Test
    void everyPaymentIsAskedAboutTwice_betweenItsPageClosingAndItsStockBeingReleased() {
        Instant made = Instant.parse("2026-11-28T10:00:00Z");
        for (int lateSeconds = 0; lateSeconds < 60; lateSeconds++) {
            List<Duration> asked = new ArrayList<>();
            Instant due = made.plus(Duration.ofMinutes(3));
            for (Instant run = made.plusSeconds(lateSeconds);
                 run.isBefore(made.plus(Duration.ofHours(30))); run = run.plusSeconds(60)) {
                if (asked.size() < PaymentPendingCancelJob.MAX_STATUS_QUERIES && !run.isBefore(due)) {
                    asked.add(Duration.between(made, run));
                    due = PaymentPendingCancelJob.nextQueryAt(made, asked.size(), run);
                }
            }
            assertThat(asked).as("late by %s s", lateSeconds).hasSize(PaymentPendingCancelJob.MAX_STATUS_QUERIES);
            for (int closes = 1; closes <= 15; closes++) {
                Duration from = Duration.ofMinutes(closes);
                Duration until = from.plusMinutes(15);
                assertThat(asked.stream().filter(at -> at.compareTo(from) > 0 && at.compareTo(until) < 0).count())
                        .as("page closes after %s min, job late by %s s, asked %s", closes, lateSeconds, asked)
                        .isGreaterThanOrEqualTo(2);
            }
        }
    }

    @Test
    void itRunsEveryMinute_butNotInTheTests() throws NoSuchMethodException {
        assertThat(PaymentPendingCancelJob.class.getMethod("cancelPendingPayments").getAnnotation(Scheduled.class))
                .isNotNull()
                .extracting(Scheduled::cron).isEqualTo("${bidtix.payment.status-query-cron:30 * * * * *}");
        assertThat(scheduledTasks.getScheduledTasks())
                .noneMatch(task -> task.getTask() instanceof CronTask cron
                        && cron.toString().contains("PaymentPendingCancelJob"));
    }

    @Test
    void aPaymentVnpayReportsPaid_forACancelledOrder_waitsForARefund() {
        String txnRef = pendingOnCancelledOrder();
        ANSWERS.put(txnRef, "status:00");

        job.cancelPendingPayments();

        assertThat(row(txnRef)).containsEntry("status", "REFUND_PENDING")
                .containsEntry("provider_txn_id", "Q" + txnRef.substring(0, 16))
                .containsEntry("failure_reason", "The order expired or was cancelled")
                .containsEntry("status_queries", 1);
        assertThat(requestsFor(txnRef)).singleElement().satisfies(request -> assertThat(request)
                .containsEntry("vnp_Command", "querydr").containsKey("vnp_SecureHash"));
    }

    @Test
    void aFailedTransaction_makesThePaymentFailed() {
        String txnRef = pendingOnCancelledOrder();
        ANSWERS.put(txnRef, "status:02");

        job.cancelPendingPayments();

        assertThat(row(txnRef)).containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "VNPAY query: transaction status 02");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "status:01",            // not completed
            "status:04",            // debited at the bank, unsuccessful at VNPAY
            "status:07",            // suspected fraud
            "code:91",              // no such transaction
            "unsigned-99",          // what the sandbox has answered
            "bad-signature",        // says paid, signed with another secret
            "other-reference",      // says paid, about another payment
            "refund-record",        // status 00 of a refund record, not of the payment
            "wrong-amount",         // says paid, another amount
            "500"})
    void anAnswerThatDecidesNothing_leavesThePaymentPending(String answer) {
        String txnRef = pendingOnCancelledOrder();
        ANSWERS.put(txnRef, answer);

        job.cancelPendingPayments();

        assertThat(requestsFor(txnRef)).hasSize(1);
        assertThat(row(txnRef)).containsEntry("status", "PENDING").containsEntry("provider_txn_id", null)
                .containsEntry("status_queries", 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "status:01",      // not completed, a day after the payment was made
            "code:91",        // no such transaction
            "unsigned-91"})   // the same without a signature, as VNPAY's error answers come
    void afterTheLastQuery_aPaymentNobodyPaid_isCancelled(String answer) {
        String txnRef = dueForItsLastQuery();
        ANSWERS.put(txnRef, answer);

        job.cancelPendingPayments();

        assertThat(row(txnRef)).containsEntry("status", "CANCELLED").containsEntry("provider_txn_id", null)
                .containsEntry("status_queries", PaymentPendingCancelJob.MAX_STATUS_QUERIES);
        assertThat((String) row(txnRef).get("failure_reason")).startsWith("No payment after the last query");
    }

    @ParameterizedTest
    @ValueSource(strings = {"status:04", "status:07", "unsigned-99", "bad-signature", "other-reference",
            "refund-record", "500", "hang-up"})
    void afterTheLastQuery_aPaymentWithoutAClearAnswer_staysPending(String answer) {
        String txnRef = dueForItsLastQuery();
        ANSWERS.put(txnRef, answer);

        job.cancelPendingPayments();

        assertThat(requestsFor(txnRef)).hasSize(1);
        assertThat(row(txnRef)).containsEntry("status", "PENDING");
    }

    @Test
    void aPaymentFoundPaidAtTheLastQuery_isNotCancelled() {
        String txnRef = dueForItsLastQuery();
        ANSWERS.put(txnRef, "status:00");

        job.cancelPendingPayments();

        assertThat(row(txnRef)).containsEntry("status", "REFUND_PENDING");
    }

    @Test
    void aPaymentIsNotAskedAboutAgainBeforeItsNextTime() {
        String txnRef = pendingOnCancelledOrder();

        job.cancelPendingPayments();
        job.cancelPendingPayments();

        assertThat(requestsFor(txnRef)).hasSize(1);
        assertThat((Integer) row(txnRef).get("seconds_to_next")).isPositive();
    }

    @Test
    void theQueriesStopAtTheLimit() {
        String txnRef = pendingOnCancelledOrder();

        for (int run = 0; run < PaymentPendingCancelJob.MAX_STATUS_QUERIES + 2; run++) {
            job.cancelPendingPayments();
            jdbc.update("update payments set next_status_query_at = now() - interval '1 second' where txn_ref = ?", txnRef);
        }

        assertThat(requestsFor(txnRef)).hasSize(PaymentPendingCancelJob.MAX_STATUS_QUERIES);
    }

    @Test
    void aPaymentWithoutItsRequestTime_isNotAskedAbout() {
        String txnRef = pendingOnCancelledOrder();
        jdbc.update("update payments set requested_at = null where txn_ref = ?", txnRef);

        job.cancelPendingPayments();

        assertThat(requestsFor(txnRef)).isEmpty();
    }

    @Test
    void onePaymentThatCannotBeAskedAbout_doesNotStopTheOthers() {
        String next = pending(10);
        ANSWERS.put(next, "status:02");
        String broken = pending(5);   // newer, so it comes first
        jdbc.update("update payments set amount = ? where txn_ref = ?", 100_000_000_000_000_000L, broken);   // x100 overflows
        ANSWERS.put(broken, "status:00");

        job.cancelPendingPayments();

        assertThat(row(broken)).containsEntry("status", "PENDING");
        assertThat(row(next)).containsEntry("status", "FAILED");
    }

    @Test
    void takingAPaymentToAskAboutIt_doesNotMakeACallbackAlreadyAtWorkLose() {
        String txnRef = pendingOnCancelledOrder();
        long id = jdbc.queryForObject("select id from payments where txn_ref = ?", Long.class, txnRef);
        long versionTheCallbackRead = jdbc.queryForObject("select version from payments where id = ?", Long.class, id);
        Instant now = Instant.now();

        transactionTemplate.executeWithoutResult(tx -> payments.markStatusQueried(id, now.plusSeconds(60), now));
        int updated = transactionTemplate.execute(tx -> payments.updatePaymentStatusByTxnRef(txnRef, null,
                com.bidtix.payment.domain.PaymentStatus.FAILED, com.bidtix.payment.domain.PaymentStatus.PENDING,
                "callback", versionTheCallbackRead, now));

        assertThat(updated).isEqualTo(1);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** A PENDING payment of 1000 VND whose booking order is cancelled; returns its reference. */
    private String pendingOnCancelledOrder() {
        return pending(4);
    }

    /** The same, made this many minutes ago. */
    private String pending(int minutesAgo) {
        String txnRef = RefundFixtures.refundPending(jdbc);
        jdbc.update("update payments set status = 'PENDING', failure_reason = null, "
                + "requested_at = now() - make_interval(mins => ?) where txn_ref = ?", minutesAgo, txnRef);
        return txnRef;
    }

    /** A payment made yesterday that has had every query but one. */
    private String dueForItsLastQuery() {
        String txnRef = pending(25 * 60);
        jdbc.update("update payments set status_queries = ? where txn_ref = ?",
                PaymentPendingCancelJob.MAX_STATUS_QUERIES - 1, txnRef);
        return txnRef;
    }

    private Map<String, Object> row(String txnRef) {
        return jdbc.queryForMap("select status, provider_txn_id, failure_reason, status_queries, "
                + "extract(epoch from next_status_query_at - now())::int as seconds_to_next "
                + "from payments where txn_ref = ?", txnRef);
    }

    private static List<Map<String, String>> requestsFor(String txnRef) {
        return REQUESTS.stream().filter(request -> txnRef.equals(request.get("vnp_TxnRef"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static HttpServer startStandIn() {
        try {
            JsonMapper json = JsonMapper.builder().build();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/transaction", exchange -> {
                Map<String, String> request = json.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
                REQUESTS.add(request);
                String mode = ANSWERS.getOrDefault(request.get("vnp_TxnRef"), "code:91");
                if (mode.equals("hang-up")) {
                    exchange.close();   // the request arrived, no answer comes back
                    return;
                }
                byte[] body = json.writeValueAsBytes(answer(mode, request.get("vnp_TxnRef")));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(mode.equals("500") ? 500 : 200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> answer(String mode, String txnRef) {
        if (mode.equals("unsigned-91")) {
            return Map.of("vnp_ResponseCode", "91", "vnp_Message", "Not found");
        }
        if (mode.equals("unsigned-99") || mode.equals("500")) {
            return Map.of("vnp_ResponseCode", "99", "vnp_Message", "Unexpected error");
        }
        Map<String, String> response = new HashMap<>();
        response.put("vnp_ResponseId", UUID.randomUUID().toString().replace("-", ""));
        response.put("vnp_Command", "querydr");
        response.put("vnp_ResponseCode", mode.startsWith("code:") ? mode.substring(5) : "00");
        response.put("vnp_Message", "answer");
        response.put("vnp_TmnCode", "TESTCODE");
        response.put("vnp_TxnRef", mode.equals("other-reference") ? "someoneelse" : txnRef);
        response.put("vnp_Amount", mode.equals("wrong-amount") ? "1000"
                : mode.startsWith("paid:") ? mode.substring(5) : "100000");
        response.put("vnp_PayDate", "20261125143000");
        response.put("vnp_TransactionNo", "Q" + txnRef.substring(0, 16));
        response.put("vnp_TransactionType", mode.equals("refund-record") ? "02" : "01");
        response.put("vnp_TransactionStatus", mode.startsWith("status:") ? mode.substring(7) : "00");
        // the signed values in VNPAY's order; the ones this answer lacks are empty
        String signed = Stream.of("vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message", "vnp_TmnCode",
                        "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo",
                        "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo", "vnp_PromotionCode",
                        "vnp_PromotionAmount")
                .map(field -> response.getOrDefault(field, ""))
                .collect(Collectors.joining("|"));
        response.put("vnp_SecureHash", STAND_IN_SIGNER.hash(mode.equals("bad-signature") ? signed + "x" : signed));
        return response;
    }
}
