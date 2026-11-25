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

/**
 * What querydr's answer says about a requested refund. Only a refund record (type 02) can settle it: a payment record
 * (type 01) with status 00 only says the customer paid.
 */
@TestPropertySource(properties = {
        "bidtix.payment.vnpay.tmn-code=TESTCODE",
        "bidtix.payment.vnpay.hash-secret=TESTSECRET"})
class RefundQueryHandlingTest extends PostgresIntegrationTest {

    private static final String WHY = "The order expired or was cancelled";

    @Autowired RefundPaymentJob job;
    @Autowired PaymentRepository payments;
    @Autowired VnpaySigner signer;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aSuccessfulFullRefund_makesThePaymentRefunded() {
        Payment payment = requested();

        job.handleQueryResponse(answer(payment, "00", "02", "00"), payment);

        assertThat(row(payment)).containsEntry("status", "REFUNDED").containsEntry("version", 1L);
    }

    @Test
    void aRejectedRefund_isRecorded_andThePaymentStaysWaiting() {
        Payment payment = requested();

        job.handleQueryResponse(answer(payment, "00", "02", "09"), payment);

        assertThat(row(payment)).containsEntry("status", "REFUND_PENDING");
        assertThat((String) row(payment).get("failure_reason")).startsWith(WHY).contains("rejected").contains("09");
    }

    @Test
    void aRefundStillBeingProcessed_changesNothing() {
        for (String status : List.of("05", "06")) {
            Payment payment = requested();

            job.handleQueryResponse(answer(payment, "00", "02", status), payment);

            assertUnchanged(payment);
        }
    }

    @Test
    void onlyAFullRefundRecordSettlesAnything() {
        // 01: the payment itself; 03: a partial refund, which this application never asks for
        for (String type : List.of("01", "03", "09", "")) {
            for (String status : List.of("00", "09")) {
                Payment payment = requested();

                job.handleQueryResponse(answer(payment, "00", type, status), payment);

                assertUnchanged(payment);
            }
        }
    }

    @Test
    void anUnclearTransactionStatus_changesNothing() {
        for (String status : List.of("01", "02", "07", "")) {
            Payment payment = requested();

            job.handleQueryResponse(answer(payment, "00", "02", status), payment);

            assertUnchanged(payment);
        }
    }

    @Test
    void aQueryVnpayDidNotProcess_changesNothing() {
        for (String code : List.of("91", "94", "99")) {
            Payment payment = requested();

            job.handleQueryResponse(answer(payment, code, "02", "00"), payment);

            assertUnchanged(payment);
        }
    }

    @Test
    void anAnswerThatCannotBeTrusted_changesNothing() {
        Payment payment = requested();
        Map<String, String> changed = answer(payment, "00", "02", "05");
        changed.put("vnp_TransactionStatus", "00");   // changed after signing
        job.handleQueryResponse(changed, payment);

        Map<String, String> unsigned = answer(payment, "00", "02", "00");
        unsigned.remove("vnp_SecureHash");
        job.handleQueryResponse(unsigned, payment);

        job.handleQueryResponse(answer(requested(), "00", "02", "00"), payment);   // about another payment

        assertUnchanged(payment);
    }

    @Test
    void aPaymentWhoseRefundWasNeverRequested_isNotMarkedRefunded() {
        Payment payment = requested();
        jdbc.update("update payments set refund_requested_at = null where id = ?", payment.getId());

        job.handleQueryResponse(answer(payment, "00", "02", "00"), payment);

        assertUnchanged(payment);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** VNPAY's signed answer to querydr about this payment. */
    private Map<String, String> answer(Payment payment, String responseCode, String type, String transactionStatus) {
        Map<String, String> response = new HashMap<>();
        response.put("vnp_ResponseId", UUID.randomUUID().toString().replace("-", ""));
        response.put("vnp_Command", "querydr");
        response.put("vnp_ResponseCode", responseCode);
        response.put("vnp_Message", "answer");
        response.put("vnp_TmnCode", "TESTCODE");
        response.put("vnp_TxnRef", payment.getTxnRef());
        response.put("vnp_TransactionType", type);
        response.put("vnp_TransactionStatus", transactionStatus);
        String signed = String.join("|", List.of("vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message",
                        "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo",
                        "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo", "vnp_PromotionCode",
                        "vnp_PromotionAmount").stream()
                .map(field -> response.getOrDefault(field, ""))
                .toList());
        response.put("vnp_SecureHash", signer.hash(signed));
        return response;
    }

    /** A REFUND_PENDING payment whose refund has been requested. */
    private Payment requested() {
        String txnRef = RefundFixtures.refundPending(jdbc);
        jdbc.update("update payments set refund_requested_at = now() where txn_ref = ?", txnRef);
        return payments.findByProviderAndTxnRef(com.bidtix.payment.domain.PaymentProvider.VNPAY, txnRef).orElseThrow();
    }

    private Map<String, Object> row(Payment payment) {
        return jdbc.queryForMap("select status, version, failure_reason from payments where id = ?", payment.getId());
    }

    private void assertUnchanged(Payment payment) {
        assertThat(row(payment)).containsEntry("status", "REFUND_PENDING").containsEntry("failure_reason", WHY)
                .containsEntry("version", 0L);
    }
}
