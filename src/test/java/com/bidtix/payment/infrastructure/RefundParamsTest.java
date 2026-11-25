package com.bidtix.payment.infrastructure;

import com.bidtix.payment.domain.Payment;
import com.bidtix.payment.domain.PaymentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The parameters the refund job sends to VNPAY's two APIs: refund and querydr. Each has its own fields and signature. */
class RefundParamsTest {

    private final VnpayProperties properties = new VnpayProperties("TESTCODE", "TESTSECRET", "", "", "", "", "2.1.0",
            "pay", "refund", "querydr", 15);
    private final VnpaySigner signer = new VnpaySigner(properties);
    private final RefundPaymentJob job = new RefundPaymentJob(null, properties, signer, null,
            Clock.fixed(Instant.parse("2026-01-01T04:00:00Z"), ZoneOffset.UTC));
    private final Payment payment = new Payment(1L, PaymentProvider.VNPAY, "abc123", 10_000,
            Instant.parse("2026-01-01T03:31:11Z"));

    @Test
    void aQueryCarriesOnlyItsFields_signedItsWay() {
        Map<String, String> params = build("querydr");

        assertThat(params).containsOnlyKeys("vnp_RequestId", "vnp_Version", "vnp_Command", "vnp_TmnCode", "vnp_TxnRef",
                "vnp_TransactionDate", "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo", "vnp_SecureHash");
        assertThat(params).containsEntry("vnp_Command", "querydr")
                .containsEntry("vnp_TransactionDate", "20260101103111")   // Vietnam time
                .containsEntry("vnp_CreateDate", "20260101110000")
                .containsEntry("vnp_OrderInfo", "Truy van giao dich abc123")
                .containsEntry("vnp_SecureHash", signer.queryRequestHash(params));
    }

    @Test
    void aRefundStillCarriesTheAmountAndWhoAsked_signedItsWay() {
        Map<String, String> params = build("refund");

        assertThat(params).containsEntry("vnp_Command", "refund").containsEntry("vnp_TransactionType", "02")
                .containsEntry("vnp_Amount", "1000000").containsEntry("vnp_CreateBy", "system")
                .containsEntry("vnp_OrderInfo", "Hoan tien giao dich abc123")
                .containsEntry("vnp_SecureHash", signer.refundRequestHash(params))
                .doesNotContainKey("vnp_TransactionNo");   // this payment has none
    }

    @Test
    void anotherCommandIsABug() {
        assertThatThrownBy(() -> build("pay")).isInstanceOf(IllegalArgumentException.class);
    }

    private Map<String, String> build(String command) {
        return ReflectionTestUtils.invokeMethod(job, "buildVnPayParams", payment, command);
    }
}
