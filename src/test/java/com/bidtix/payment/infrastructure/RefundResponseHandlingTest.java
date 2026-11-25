package com.bidtix.payment.infrastructure;

import com.bidtix.payment.domain.Payment;
import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What the refund job does with VNPAY's answer: signature first, then the response code, then the transaction status. */
@TestPropertySource(properties = {
        "bidtix.payment.vnpay.tmn-code=TESTCODE",
        "bidtix.payment.vnpay.hash-secret=TESTSECRET"})
class RefundResponseHandlingTest extends PostgresIntegrationTest {

    private static final String WHY = "The order expired or was cancelled";

    @Autowired RefundPaymentJob job;
    @Autowired PaymentRepository payments;
    @Autowired VnpaySigner signer;
    @Autowired JdbcTemplate jdbc;

    @Test
    void anAnswerWithAWrongSignature_changesNothing() {
        Payment payment = refundRequested(WHY);
        Map<String, String> response = answer(payment, "91", null);
        response.put("vnp_ResponseCode", "99");   // changed after signing

        job.handleRefundResponse(response, payment);

        assertUnchanged(payment);
    }

    @Test
    void anUnsignedError_isNotedWithItsCode_andNothingElseChanges() {
        Payment payment = refundRequested(WHY);

        job.handleRefundResponse(new HashMap<>(Map.of("vnp_ResponseCode", "99", "vnp_Message", "Unexpected error")), payment);

        assertThat(row(payment)).containsEntry("status", "REFUND_PENDING");
        assertThat(row(payment).get("refund_requested_at")).isNotNull();
        assertThat((String) row(payment).get("failure_reason")).startsWith(WHY).contains("unsigned").contains("99");
    }

    @Test
    void anUnsignedSuccess_isIgnored() {
        Payment unsigned = refundRequested(WHY);
        assertThat(job.handleRefundResponse(new HashMap<>(Map.of("vnp_ResponseCode", "99")), unsigned))
                .as("nothing to confirm").isFalse();

        Payment payment = refundRequested(WHY);

        job.handleRefundResponse(new HashMap<>(Map.of("vnp_ResponseCode", "00", "vnp_TransactionStatus", "05")), payment);

        assertUnchanged(payment);
    }

    @Test
    void aSignedAnswerAboutAnotherPayment_changesNothing() {
        Payment payment = refundRequested(WHY);
        Map<String, String> response = answer(refundRequested(WHY), "91", null);

        job.handleRefundResponse(response, payment);

        assertUnchanged(payment);
    }

    @Test
    void aRefundStillBeingProcessed_keepsThePaymentAsItIs() {
        for (Map.Entry<String, String> outcome : List.of(Map.entry("00", "05"), Map.entry("00", "06"), Map.entry("94", ""))) {
            Payment payment = refundRequested(WHY);

            job.handleRefundResponse(answer(payment, outcome.getKey(), outcome.getValue()), payment);

            assertUnchanged(payment);
        }
    }

    @Test
    void aTransactionStatusWithNoClearMeaning_isNotTakenForARefund() {
        for (String status : List.of("00", "07", "")) {
            Payment payment = refundRequested(WHY);

            job.handleRefundResponse(answer(payment, "00", status), payment);

            assertUnchanged(payment);
        }
    }

    @Test
    void aRejectedRefund_isRecordedNextToWhyItWasNeeded() {
        Payment payment = refundRequested(WHY);

        job.handleRefundResponse(answer(payment, "00", "09"), payment);

        assertThat(row(payment)).containsEntry("status", "REFUND_PENDING");
        assertThat((String) row(payment).get("failure_reason")).startsWith(WHY).contains("rejected").contains("09");
        assertThat(row(payment).get("refund_requested_at")).as("not requested again by itself").isNotNull();
    }

    @Test
    void arequestVnpayDidNotProcess_isRecordedWithItsCode() {
        for (String code : List.of("02", "03", "91", "95", "97", "99")) {
            Payment payment = refundRequested(null);

            job.handleRefundResponse(answer(payment, code, null), payment);

            assertThat(row(payment)).containsEntry("status", "REFUND_PENDING");
            assertThat((String) row(payment).get("failure_reason")).contains("response code " + code);
        }
    }

    @Test
    void aLongReason_isCutToTheColumnsLength() {
        Payment payment = refundRequested("x".repeat(250));

        job.handleRefundResponse(answer(payment, "91", null), payment);

        assertThat((String) row(payment).get("failure_reason")).hasSize(255).startsWith("xxx");
    }

    @Test
    void aPaymentThatNoLongerWaitsForItsRefund_isLeftAlone() {
        Payment payment = refundRequested(WHY);
        jdbc.update("update payments set status = 'REFUNDED' where id = ?", payment.getId());

        job.handleRefundResponse(answer(payment, "91", null), payment);

        assertThat(row(payment)).containsEntry("status", "REFUNDED").containsEntry("failure_reason", WHY);
    }

    // ---- when querydr is asked ---------------------------------------------------------------------

    @Test
    void onlyAnAcceptedRequest_isToBeConfirmed() {
        // both the response code and the transaction status count; 94 means an earlier request is being processed
        for (String[] accepted : new String[][]{{"00", "00"}, {"00", "05"}, {"00", "06"}, {"94", null}}) {
            Payment payment = refundRequested(WHY);

            assertThat(job.handleRefundResponse(answer(payment, accepted[0], accepted[1]), payment))
                    .as(accepted[0] + "/" + accepted[1]).isTrue();
            assertThat(row(payment)).containsEntry("status", "REFUND_PENDING");
        }
        for (String[] other : new String[][]{{"00", "09"}, {"00", "07"}, {"00", null}, {"91", "00"}, {"97", "05"}, {"99", "00"}}) {
            Payment payment = refundRequested(WHY);

            assertThat(job.handleRefundResponse(answer(payment, other[0], other[1]), payment))
                    .as(other[0] + "/" + other[1]).isFalse();
        }
        Payment payment = refundRequested(WHY);
        Map<String, String> changed = answer(payment, "00", "05");
        changed.put("vnp_TransactionStatus", "00");
        assertThat(job.handleRefundResponse(changed, payment)).as("wrong signature").isFalse();
        assertThat(job.handleRefundResponse(answer(refundRequested(WHY), "00", "05"), payment)).as("another payment").isFalse();
    }

    @Test
    void aRejectedRefund_isNotAskedAboutAgain() {
        Payment payment = refundRequested(WHY);

        job.handleRefundResponse(answer(payment, "00", "09"), payment);

        assertThat(jdbc.queryForObject("select refund_queries from payments where id = ?", Integer.class, payment.getId()))
                .isEqualTo(RefundPaymentJob.MAX_REFUND_QUERIES);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** VNPAY's signed answer about this payment; a null status leaves the field out. */
    private Map<String, String> answer(Payment payment, String responseCode, String transactionStatus) {
        Map<String, String> response = new HashMap<>();
        response.put("vnp_ResponseId", UUID.randomUUID().toString().replace("-", ""));
        response.put("vnp_Command", "refund");
        response.put("vnp_ResponseCode", responseCode);
        response.put("vnp_Message", "answer");
        response.put("vnp_TmnCode", "TESTCODE");
        response.put("vnp_TxnRef", payment.getTxnRef());
        if (transactionStatus != null) {
            response.put("vnp_TransactionStatus", transactionStatus);
        }
        String signed = String.join("|", List.of("vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message",
                        "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo",
                        "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo").stream()
                .map(field -> response.getOrDefault(field, ""))
                .toList());
        response.put("vnp_SecureHash", signer.hash(signed));
        return response;
    }

    /** A REFUND_PENDING payment whose refund has been requested, on an order of its own. */
    private Payment refundRequested(String failureReason) {
        String run = UUID.randomUUID().toString().replace("-", "");
        long user = jdbc.queryForObject("insert into users (email, password_hash, full_name, role) "
                + "values (?, 'hash', 'Payer', 'CUSTOMER') returning id", Long.class, "answer-" + run + "@test.dev");
        long venue = jdbc.queryForObject("insert into venues (name, address, city) values ('Arena', '1 Street', 'Hanoi') "
                + "returning id", Long.class);
        long event = jdbc.queryForObject("insert into events (organizer_id, venue_id, title, starts_at, ends_at, "
                + "sale_starts_at, sale_ends_at) values (?, ?, 'Concert', now() + interval '30 days', "
                + "now() + interval '31 days', now(), now() + interval '29 days') returning id", Long.class, user, venue);
        long order = jdbc.queryForObject("insert into orders (user_id, event_id, source, status, total_amount, expires_at, "
                        + "order_code) values (?, ?, 'BOOKING', 'CANCELLED', 1000, now(), ?) returning id", Long.class,
                user, event, "ORDER_" + run.substring(0, 13).toUpperCase());
        long payment = jdbc.queryForObject("insert into payments (order_id, provider, txn_ref, amount, status, "
                        + "failure_reason, requested_at, refund_requested_at) "
                        + "values (?, 'VNPAY', ?, 1000, 'REFUND_PENDING', ?, now(), now()) returning id", Long.class,
                order, run, failureReason);
        return payments.findById(payment).orElseThrow();
    }

    private Map<String, Object> row(Payment payment) {
        return jdbc.queryForMap("select status, version, failure_reason, refund_requested_at from payments where id = ?",
                payment.getId());
    }

    private void assertUnchanged(Payment payment) {
        assertThat(row(payment)).containsEntry("status", "REFUND_PENDING").containsEntry("failure_reason", WHY)
                .containsEntry("version", 0L);
    }
}
