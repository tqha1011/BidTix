package com.bidtix.payment.service;

import io.micrometer.core.instrument.MeterRegistry;
import com.bidtix.support.Counters;
import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.infrastructure.OrderCancellationJob;
import com.bidtix.booking.service.BookingService;
import com.bidtix.booking.service.GeneralAdmissionFixtures;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.payment.dtos.TransactionResponse;
import com.bidtix.payment.dtos.VnPayResponse;
import com.bidtix.payment.infrastructure.VnpaySigner;
import com.bidtix.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** VNPAY's confirmation (IPN): which {@code RspCode} it gets, what is stored, and what happens to the payment and its order. */
@TestPropertySource(properties = {
        "bidtix.payment.vnpay.tmn-code=TESTCODE",
        "bidtix.payment.vnpay.hash-secret=TESTSECRET"})
class VnpayIpnTest extends PostgresIntegrationTest {

    private static final String PAY_DATE = "20261125143000";
    private static final Instant PAID_AT = LocalDateTime.parse(PAY_DATE, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            .atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();

    @Autowired PaymentService service;
    @Autowired MeterRegistry meters;
    @Autowired VnpaySigner signer;
    @Autowired BookingService bookingService;
    @Autowired OrderCancellationJob cancellationJob;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper json;

    /** An unpaid booking order and one payment attempt for it. */
    private record Attempt(UUID user, String orderCode, long amount, String txnRef) {
    }

    @Test
    void aPaidTransaction_marksThePaymentSucceeded_andTheOrderPaidAtVnpaysTime() {
        double countedBefore = ipnAnswers("00");
        Attempt attempt = standingAttempt(2);

        TransactionResponse answer = send(paid(attempt));

        assertThat(answer.RspCode()).isEqualTo("00");
        assertThat(payment(attempt)).containsEntry("status", "SUCCEEDED").containsEntry("provider_txn_id", numberOf(attempt))
                .containsEntry("failure_reason", null);
        assertThat(orderStatus(attempt)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select paid_at from orders where order_code = ?", Timestamp.class, attempt.orderCode())
                .toInstant()).isEqualTo(PAID_AT);
        assertThat(tickets(attempt)).isEqualTo(2);
        assertThat(ipnAnswers("00")).isEqualTo(countedBefore + 1);
    }

    @Test
    void aWrongSignature_isAnswered97_andChangesNothing() {
        double countedBefore = ipnAnswers("97");
        Attempt attempt = standingAttempt(1);
        Map<String, String> params = paid(attempt);
        params.put("vnp_BankCode", "OTHER");   // changed after signing

        assertThat(send(params).RspCode()).isEqualTo("97");

        assertUntouched(attempt);
        assertThat(events(attempt.txnRef())).as("anyone can call the route: unsigned requests are not stored").isEmpty();
        assertThat(ipnAnswers("97")).isEqualTo(countedBefore + 1);
    }

    @Test
    void anUnknownReference_isAnswered01() {
        Attempt attempt = standingAttempt(1);
        Map<String, String> params = unsigned(attempt, numberOf(attempt));
        String unknown = "unknown" + attempt.txnRef().substring(0, 20);
        params.put("vnp_TxnRef", unknown);

        assertThat(send(sign(params)).RspCode()).isEqualTo("01");

        assertUntouched(attempt);
        assertStoredButNotProcessed(unknown);
    }

    @Test
    void anAmountThatDiffers_isAnswered04_andChangesNothing() {
        Attempt attempt = standingAttempt(1);
        Map<String, String> params = unsigned(attempt, numberOf(attempt));
        params.put("vnp_Amount", String.valueOf(attempt.amount()));   // not times 100

        assertThat(send(sign(params)).RspCode()).isEqualTo("04");

        assertUntouched(attempt);
        assertStoredButNotProcessed(attempt.txnRef());
    }

    @Test
    void aSecondDelivery_isAnswered02_andIssuesNothingMore() {
        Attempt attempt = standingAttempt(2);
        Map<String, String> params = paid(attempt);
        send(params);
        Object processedAt = events(attempt.txnRef()).getFirst().get("processed_at");

        assertThat(send(params).RspCode()).isEqualTo("02");

        assertThat(events(attempt.txnRef())).singleElement()
                .satisfies(event -> assertThat(event.get("processed_at")).isEqualTo(processedAt));

        assertThat(payment(attempt)).containsEntry("status", "SUCCEEDED");
        assertThat(tickets(attempt)).isEqualTo(2);
    }

    @Test
    void aFailedTransaction_marksThePaymentFailed_andLeavesTheOrderPayable() {
        Attempt first = standingAttempt(1);
        Attempt second = standingAttempt(1);

        for (Attempt attempt : List.of(first, second)) {   // VNPAY sends 0 as the number of every failed one
            Map<String, String> params = unsigned(attempt, "0");
            params.put("vnp_ResponseCode", "24");
            params.put("vnp_TransactionStatus", "02");

            assertThat(send(sign(params)).RspCode()).isEqualTo("00");

            assertThat(payment(attempt)).containsEntry("status", "FAILED").containsEntry("provider_txn_id", null);
            assertThat((String) payment(attempt).get("failure_reason")).contains("24");
            assertThat(orderStatus(attempt)).isEqualTo("PENDING_PAYMENT");
            assertProcessed(attempt.txnRef());
        }
    }

    @Test
    void responseCode00WithAnotherTransactionStatus_isNotAPayment() {
        Attempt attempt = standingAttempt(1);
        Map<String, String> params = unsigned(attempt, numberOf(attempt));
        params.put("vnp_TransactionStatus", "02");

        assertThat(send(sign(params)).RspCode()).isEqualTo("00");

        assertThat(payment(attempt)).containsEntry("status", "FAILED");
        assertThat(orderStatus(attempt)).isEqualTo("PENDING_PAYMENT");
        assertThat(tickets(attempt)).isZero();
    }

    @Test
    void moneyForACancelledOrder_isMarkedForRefund() {
        Attempt attempt = standingAttempt(1);
        jdbc.update("update orders set status = 'CANCELLED' where order_code = ?", attempt.orderCode());

        assertThat(send(paid(attempt)).RspCode()).isEqualTo("00");

        assertThat(payment(attempt)).containsEntry("status", "REFUND_PENDING").containsEntry("provider_txn_id", numberOf(attempt));
        assertThat(payment(attempt).get("failure_reason")).isNotNull();
        assertThat(orderStatus(attempt)).isEqualTo("CANCELLED");
        assertThat(tickets(attempt)).isZero();
    }

    @Test
    void aSecondPaymentForAPaidOrder_isMarkedForRefund() {
        Attempt first = standingAttempt(1);
        Attempt second = anotherAttempt(first);
        send(paid(first));

        assertThat(send(paid(second)).RspCode()).isEqualTo("00");

        assertThat(payment(first)).containsEntry("status", "SUCCEEDED");
        assertThat(payment(second)).containsEntry("status", "REFUND_PENDING").containsEntry("provider_txn_id", numberOf(second));
        assertThat(tickets(first)).isEqualTo(1);
    }

    @Test
    void whenASeatHoldWasLost_theOrderIsRolledBack_andThePaymentMarkedForRefund() {
        Fixture f = fixture();
        UUID kept = f.seatedSeatIds().get(0);
        UUID lost = f.seatedSeatIds().get(1);
        UUID user = f.userIds().get(0);
        String code = bookingService.bookTicket(
                new BookingTicketRequest(f.eventId(), f.seatedZoneId(), List.of(kept, lost), null), user, UUID.randomUUID().toString()).orderCode();
        Attempt attempt = attempt(user, code);
        jdbc.update("update seats set status = 'AVAILABLE', held_by_order_id = null, hold_expires_at = null "
                + "where public_id = ?", lost);

        assertThat(send(paid(attempt)).RspCode()).isEqualTo("00");

        assertThat(payment(attempt)).containsEntry("status", "REFUND_PENDING").containsEntry("provider_txn_id", numberOf(attempt));
        assertThat(orderStatus(attempt)).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("select status from seats where public_id = ?", String.class, kept)).isEqualTo("HELD");
        assertThat(tickets(attempt)).isZero();
        assertProcessed(attempt.txnRef());
    }

    @Test
    void anUnexpectedError_isAnswered99_soVnpayRetries_andNothingChanges() {
        Attempt attempt = standingAttempt(1);
        Map<String, String> params = unsigned(attempt, numberOf(attempt));
        params.put("vnp_PayDate", "not-a-date");

        assertThat(send(sign(params)).RspCode()).isEqualTo("99");

        assertUntouched(attempt);
        assertStoredButNotProcessed(attempt.txnRef());
    }

    @Test
    void theSameConfirmationInParallel_paysTheOrderOnce() throws Exception {
        Attempt attempt = standingAttempt(2);
        Map<String, String> params = paid(attempt);
        List<Callable<TransactionResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> send(params));
        }

        List<String> codes = runTogether(calls);

        assertThat(codes).containsOnlyOnce("00");
        assertThat(codes.stream().filter("02"::equals)).hasSize(7);
        assertThat(payment(attempt)).containsEntry("status", "SUCCEEDED");
        assertThat(tickets(attempt)).isEqualTo(2);
        assertProcessed(attempt.txnRef());
    }

    @Test
    void twoPaymentsForOneOrderInParallel_oneSucceedsAndTheOtherIsRefunded() throws Exception {
        Attempt first = standingAttempt(2);
        Attempt second = anotherAttempt(first);
        Map<String, String> firstParams = paid(first);
        Map<String, String> secondParams = paid(second);

        List<String> codes = runTogether(List.of(() -> send(firstParams), () -> send(secondParams)));

        assertThat(codes).containsExactly("00", "00");
        assertThat(List.of(payment(first).get("status"), payment(second).get("status")))
                .containsExactlyInAnyOrder("SUCCEEDED", "REFUND_PENDING");
        assertThat(tickets(first)).isEqualTo(2);
    }

    @Test
    void aConfirmation_isStoredWithItsPayload_andMarkedProcessed() {
        Attempt attempt = standingAttempt(1);
        Map<String, String> params = paid(attempt);

        send(params);

        assertThat(events(attempt.txnRef())).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("provider", "VNPAY")
                    .containsEntry("provider_event_id", attempt.txnRef() + ":00");
            assertThat(event.get("processed_at")).isNotNull();
            assertThat(json.readValue((String) event.get("payload"), Map.class)).isEqualTo(params);
        });
    }

    @Test
    void aStoredButUnprocessedEvent_doesNotStopTheNextDelivery() {
        Attempt attempt = standingAttempt(1);
        // an earlier delivery was stored, then failed before it could be processed
        jdbc.update("insert into payment_webhook_events (provider, provider_event_id, payload) "
                + "values ('VNPAY', ?, '{}'::jsonb)", attempt.txnRef() + ":00");

        assertThat(send(paid(attempt)).RspCode()).isEqualTo("00");

        assertThat(payment(attempt)).containsEntry("status", "SUCCEEDED");
        assertProcessed(attempt.txnRef());
    }

    @Test
    void aCallbackThatArrivesJustAfterTheDeadline_isStillApplied() {
        Attempt attempt = standingAttempt(1);
        jdbc.update("update orders set expires_at = now() - interval '1 minute' where order_code = ?", attempt.orderCode());
        cancellationJob.cancelExpiredOrders();   // runs before VNPAY's call gets through

        assertThat(send(paid(attempt)).RspCode()).isEqualTo("00");

        assertThat(payment(attempt)).containsEntry("status", "SUCCEEDED");
        assertThat(orderStatus(attempt)).isEqualTo("PAID");
        assertThat(tickets(attempt)).isEqualTo(1);
    }

    // ---- a success that arrives after we gave up on the payment ----------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELLED"})
    void aLateSuccess_forAnOrderStillWaiting_issuesTheTickets(String givenUpAs) {
        Attempt attempt = standingAttempt(2);
        giveUp(attempt, givenUpAs);

        assertThat(send(paid(attempt)).RspCode()).isEqualTo("00");

        assertThat(payment(attempt)).containsEntry("status", "SUCCEEDED").containsEntry("provider_txn_id", numberOf(attempt))
                .containsEntry("failure_reason", null);
        assertThat(orderStatus(attempt)).isEqualTo("PAID");
        assertThat(tickets(attempt)).isEqualTo(2);
        assertProcessed(attempt.txnRef());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELLED"})
    void aLateSuccess_forACancelledOrder_isMarkedForRefund(String givenUpAs) {
        Attempt attempt = standingAttempt(1);
        giveUp(attempt, givenUpAs);
        jdbc.update("update orders set expires_at = now() - interval '1 minute' where order_code = ?", attempt.orderCode());
        cancellationJob.cancelExpiredOrders();

        assertThat(send(paid(attempt)).RspCode()).isEqualTo("00");

        assertThat(payment(attempt)).containsEntry("status", "REFUND_PENDING")
                .containsEntry("provider_txn_id", numberOf(attempt));
        assertThat(orderStatus(attempt)).isEqualTo("CANCELLED");
        assertThat(tickets(attempt)).isZero();
        assertProcessed(attempt.txnRef());
    }

    @Test
    void aLateSuccess_forAnOrderPaidByAnotherAttempt_isMarkedForRefund() {
        Attempt first = standingAttempt(1);
        Attempt second = anotherAttempt(first);
        giveUp(first, "CANCELLED");
        send(paid(second));

        assertThat(send(paid(first)).RspCode()).isEqualTo("00");

        assertThat(payment(first)).containsEntry("status", "REFUND_PENDING");
        assertThat(payment(second)).containsEntry("status", "SUCCEEDED");
        assertThat(tickets(first)).isEqualTo(1);
    }

    @Test
    void aLateSuccess_whoseSeatHoldWasLost_isMarkedForRefund() {
        Fixture f = fixture();
        UUID seat = f.seatedSeatIds().get(0);
        UUID user = f.userIds().get(0);
        String code = bookingService.bookTicket(
                new BookingTicketRequest(f.eventId(), f.seatedZoneId(), List.of(seat), null), user, UUID.randomUUID().toString()).orderCode();
        Attempt attempt = attempt(user, code);
        giveUp(attempt, "FAILED");
        jdbc.update("update seats set status = 'AVAILABLE', held_by_order_id = null, hold_expires_at = null "
                + "where public_id = ?", seat);

        assertThat(send(paid(attempt)).RspCode()).isEqualTo("00");

        assertThat(payment(attempt)).containsEntry("status", "REFUND_PENDING")
                .containsEntry("provider_txn_id", numberOf(attempt));
        assertThat(tickets(attempt)).isZero();
        assertProcessed(attempt.txnRef());
    }

    @Test
    void aLateFailure_changesNothing() {
        Attempt attempt = standingAttempt(1);
        giveUp(attempt, "CANCELLED");
        Map<String, String> params = unsigned(attempt, numberOf(attempt));
        params.put("vnp_ResponseCode", "24");
        params.put("vnp_TransactionStatus", "02");

        assertThat(send(sign(params)).RspCode()).isEqualTo("02");

        assertThat(payment(attempt)).containsEntry("status", "CANCELLED").containsEntry("failure_reason", "gave up");
    }

    @Test
    void theSameLateSuccessInParallel_paysTheOrderOnce() throws Exception {
        Attempt attempt = standingAttempt(2);
        giveUp(attempt, "CANCELLED");
        Map<String, String> params = paid(attempt);
        List<Callable<TransactionResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> send(params));
        }

        List<String> codes = runTogether(calls);

        assertThat(codes).containsOnlyOnce("00");
        assertThat(codes.stream().filter("02"::equals)).hasSize(7);
        assertThat(payment(attempt)).containsEntry("status", "SUCCEEDED");
        assertThat(tickets(attempt)).isEqualTo(2);
        assertProcessed(attempt.txnRef());
    }

    /** As the query job leaves a payment it saw fail, or found unpaid at its last query. */
    private void giveUp(Attempt attempt, String status) {
        jdbc.update("update payments set status = ?, failure_reason = 'gave up', version = version + 1 "
                + "where txn_ref = ?", status, attempt.txnRef());
    }

    @Test
    void theAnswerUsesVnpaysFieldNames() {
        var body = json.readTree(json.writeValueAsString(TransactionResponse.create("00", "Confirm Success")));

        assertThat(body.propertyNames()).containsExactlyInAnyOrder("RspCode", "Message");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Fixture fixture() {
        return GeneralAdmissionFixtures.create(em, tx, 10, 1);
    }

    private Attempt standingAttempt(int quantity) {
        Fixture f = fixture();
        UUID user = f.userIds().get(0);
        String code = bookingService.bookTicket(new BookingTicketRequest(f.eventId(), f.zoneId(), null, quantity), user, UUID.randomUUID().toString())
                .orderCode();
        return attempt(user, code);
    }

    private Attempt anotherAttempt(Attempt attempt) {
        return attempt(attempt.user(), attempt.orderCode());
    }

    private Attempt attempt(UUID user, String orderCode) {
        String url = service.createUrlPayment(orderCode, user, "203.0.113.7");
        String txnRef = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("vnp_TxnRef");
        long amount = jdbc.queryForObject("select total_amount from orders where order_code = ?", Long.class, orderCode);
        return new Attempt(user, orderCode, amount, txnRef);
    }

    /** The parameters of a successful transaction, without the signature. */
    private static Map<String, String> unsigned(Attempt attempt, String transactionNo) {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_TmnCode", "TESTCODE");
        params.put("vnp_Amount", String.valueOf(attempt.amount() * 100));
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_PayDate", PAY_DATE);
        params.put("vnp_OrderInfo", "Thanh toan don hang");
        params.put("vnp_TransactionNo", transactionNo);
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionStatus", "00");
        params.put("vnp_TxnRef", attempt.txnRef());
        return params;
    }

    private Map<String, String> paid(Attempt attempt) {
        return sign(unsigned(attempt, numberOf(attempt)));
    }

    /** VNPAY's transaction number; unique per provider, and the database is shared by every test. */
    private static String numberOf(Attempt attempt) {
        return "TXN" + attempt.txnRef().substring(0, 16);
    }

    private Map<String, String> sign(Map<String, String> params) {
        String url = signer.signedPaymentUrl(params);
        params.put("vnp_SecureHash", url.substring(url.indexOf("vnp_SecureHash=") + "vnp_SecureHash=".length()));
        return params;
    }

    /** What the callback endpoint would do: the raw parameters, and the same ones bound to the record. */
    private TransactionResponse send(Map<String, String> p) {
        return service.processVnPayResponse(p, new VnPayResponse(p.get("vnp_TmnCode"), Long.valueOf(p.get("vnp_Amount")),
                p.get("vnp_BankCode"), p.get("vnp_BankTranNo"), p.get("vnp_CardType"), p.get("vnp_PayDate"),
                p.get("vnp_OrderInfo"), p.get("vnp_TransactionNo"), p.get("vnp_ResponseCode"),
                p.get("vnp_TransactionStatus"), p.get("vnp_TxnRef"), p.get("vnp_SecureHash")));
    }

    private static List<String> runTogether(List<Callable<TransactionResponse>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<TransactionResponse>> results = new ArrayList<>();
            for (Callable<TransactionResponse> call : calls) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<String> codes = new ArrayList<>();
            for (Future<TransactionResponse> result : results) {
                codes.add(result.get(30, TimeUnit.SECONDS).RspCode());
            }
            return codes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The stored callbacks of one payment reference. */
    private List<Map<String, Object>> events(String txnRef) {
        return jdbc.queryForList("select provider, provider_event_id, payload::text as payload, processed_at "
                + "from payment_webhook_events where provider_event_id like ?", txnRef + ":%");
    }

    private void assertProcessed(String txnRef) {
        assertThat(events(txnRef)).singleElement()
                .satisfies(event -> assertThat(event.get("processed_at")).isNotNull());
    }

    private void assertStoredButNotProcessed(String txnRef) {
        assertThat(events(txnRef)).singleElement()
                .satisfies(event -> assertThat(event.get("processed_at")).isNull());
    }

    private Map<String, Object> payment(Attempt attempt) {
        return jdbc.queryForMap("select status, provider_txn_id, failure_reason from payments where txn_ref = ?",
                attempt.txnRef());
    }

    private String orderStatus(Attempt attempt) {
        return jdbc.queryForObject("select status from orders where order_code = ?", String.class, attempt.orderCode());
    }

    private int tickets(Attempt attempt) {
        return jdbc.queryForObject("select count(*) from tickets t join orders o on o.id = t.order_id where o.order_code = ?",
                Integer.class, attempt.orderCode());
    }

    private void assertUntouched(Attempt attempt) {
        assertThat(payment(attempt)).containsEntry("status", "PENDING").containsEntry("provider_txn_id", null);
        assertThat(orderStatus(attempt)).isEqualTo("PENDING_PAYMENT");
        assertThat(tickets(attempt)).isZero();
    }

    private double ipnAnswers(String code) {
        return Counters.value(meters, "bidtix.payment.vnpay.ipn", "code", code);
    }
}
