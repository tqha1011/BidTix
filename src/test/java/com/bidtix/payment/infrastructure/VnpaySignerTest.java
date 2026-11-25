package com.bidtix.payment.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Signing and checking of VNPAY parameters. The expected hash was computed independently (Python hmac), not by this class. */
class VnpaySignerTest {

    private static final String SECRET = "TESTSECRET";
    private static final String EXPECTED_HASH =
            "44ee8ee2c3ca8baa723a04101941ea7e24158a8815c523a078ee41508bcbc4d32328d46d9c853fd0c15d7aa886bac7b755ce3603ea7f3751b52d384872a673d2";
    private static final String EXPECTED_TEXT =
            "vnp_Amount=1000000&vnp_Command=pay&vnp_CreateDate=20260101103111&vnp_CurrCode=VND&vnp_IpAddr=127.0.0.1"
                    + "&vnp_Locale=vn&vnp_OrderInfo=Thanh+toan+don+hang+ORD123&vnp_OrderType=other"
                    + "&vnp_ReturnUrl=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fv1%2Fpayments%2Fvnpay%2Freturn"
                    + "&vnp_TmnCode=TESTCODE&vnp_TxnRef=ORD123-1&vnp_Version=2.1.0";

    private final VnpaySigner signer = new VnpaySigner(
            properties("TESTCODE", SECRET));

    private static VnpayProperties properties(String tmnCode, String secret) {
        return new VnpayProperties(tmnCode, secret, "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
                "http://localhost:8080/api/v1/payments/vnpay/return", "http://localhost:8080/api/v1/payments/vnpay/ipn",
                "https://sandbox.vnpayment.vn/merchant_webapi/api/transaction", "2.1.0", "pay", "refund", "querydr", 15);
    }

    private static Map<String, String> params() {
        Map<String, String> p = new HashMap<>();
        p.put("vnp_Version", "2.1.0");
        p.put("vnp_Command", "pay");
        p.put("vnp_TmnCode", "TESTCODE");
        p.put("vnp_Amount", "1000000");
        p.put("vnp_CreateDate", "20260101103111");
        p.put("vnp_CurrCode", "VND");
        p.put("vnp_IpAddr", "127.0.0.1");
        p.put("vnp_Locale", "vn");
        p.put("vnp_OrderInfo", "Thanh toan don hang ORD123");
        p.put("vnp_OrderType", "other");
        p.put("vnp_ReturnUrl", "http://localhost:8080/api/v1/payments/vnpay/return");
        p.put("vnp_TxnRef", "ORD123-1");
        return p;
    }

    @Test
    void theSignedTextIsSortedAndEncoded() {
        assertThat(signer.signedText(params())).isEqualTo(EXPECTED_TEXT);
    }

    @Test
    void theUrlCarriesTheKnownHashAfterTheParameters() {
        assertThat(signer.signedPaymentUrl(params())).isEqualTo(
                "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?" + EXPECTED_TEXT + "&vnp_SecureHash=" + EXPECTED_HASH);
    }

    @Test
    void emptyValuesAndTheHashFieldsAreLeftOutOfTheSignedText() {
        Map<String, String> p = params();
        p.put("vnp_BankCode", "");
        p.put("vnp_SecureHash", "whatever");
        p.put("vnp_SecureHashType", "HmacSHA512");

        assertThat(signer.signedText(p)).isEqualTo(EXPECTED_TEXT);
    }

    @Test
    void aCallbackSignedWithTheSameSecretIsAccepted_inAnyHexCase() {
        Map<String, String> p = params();
        p.put("vnp_SecureHash", EXPECTED_HASH);
        assertThat(signer.verify(p)).isTrue();

        p.put("vnp_SecureHash", EXPECTED_HASH.toUpperCase());
        assertThat(signer.verify(p)).isTrue();
    }

    @Test
    void aChangedValueOrAWrongOrMissingHashIsRejected() {
        Map<String, String> changed = params();
        changed.put("vnp_SecureHash", EXPECTED_HASH);
        changed.put("vnp_Amount", "1");   // somebody lowered the amount
        assertThat(signer.verify(changed)).isFalse();

        Map<String, String> wrong = params();
        wrong.put("vnp_SecureHash", "00" + EXPECTED_HASH.substring(2));
        assertThat(signer.verify(wrong)).isFalse();

        assertThat(signer.verify(params())).isFalse();
        Map<String, String> blank = params();
        blank.put("vnp_SecureHash", " ");
        assertThat(signer.verify(blank)).isFalse();
    }

    @Test
    void aCallbackAddingAnExtraParameterIsRejected() {
        Map<String, String> p = params();
        p.put("vnp_SecureHash", EXPECTED_HASH);
        p.put("vnp_Extra", "x");

        assertThat(signer.verify(p)).isFalse();
    }

    @Test
    void anotherSecretGivesAnotherHash() {
        VnpaySigner other = new VnpaySigner(properties("TESTCODE", "OTHER"));
        Map<String, String> p = params();
        p.put("vnp_SecureHash", EXPECTED_HASH);

        assertThat(other.verify(p)).isFalse();
    }

    @Test
    void withoutASecretItFailsClearlyInsteadOfSigningWithAnEmptyKey() {
        VnpaySigner unset = new VnpaySigner(properties("", ""));

        assertThatThrownBy(() -> unset.signedPaymentUrl(params()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("VNP_HASH_SECRET");
        assertThatThrownBy(() -> unset.verify(withHash()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- refund: another format, the values only, joined with | in a fixed order -----------------------

    private static final String REFUND_REQUEST_HASH =
            "2fc1c37342204764c5a34f8b6eaf4ea4c32ca05c4dcc5e0d497740e5c189f085df7823707b4c0856c8ffb88f1ecbefcb160f17031c4120570820aca0e177649d";
    private static final String REFUND_REQUEST_HASH_WITHOUT_TRANSACTION_NO =
            "20f6904c8ee18e0a4781445038ec1c3ee271063f73ea93f682409d770969073203a4f396db17004f2c168b15c9dd6f025291fa860fd4960caacb006edf63e22c";
    private static final String REFUND_RESPONSE_HASH =
            "27a99417e9aa9b23b5c3dcfaa3e990cd644fecb1d9116cdc10356d6de55df5d443979f599de5a93e95f4c26457e444853444d337aab2e0855f05fa7c52a6b287";

    private static Map<String, String> refundRequest() {
        Map<String, String> p = new HashMap<>();
        p.put("vnp_RequestId", "req0001");
        p.put("vnp_Version", "2.1.0");
        p.put("vnp_Command", "refund");
        p.put("vnp_TmnCode", "TESTCODE");
        p.put("vnp_TransactionType", "02");
        p.put("vnp_TxnRef", "ORD123-1");
        p.put("vnp_Amount", "1000000");
        p.put("vnp_TransactionNo", "14123456");
        p.put("vnp_TransactionDate", "20260101103111");
        p.put("vnp_CreateBy", "system");
        p.put("vnp_CreateDate", "20260101110000");
        p.put("vnp_IpAddr", "127.0.0.1");
        p.put("vnp_OrderInfo", "Hoan tien don hang ORD123");
        return p;
    }

    private static Map<String, String> refundResponse() {
        Map<String, String> p = new HashMap<>();
        p.put("vnp_ResponseId", "res0001");
        p.put("vnp_Command", "refund");
        p.put("vnp_ResponseCode", "00");
        p.put("vnp_Message", "Refund success");
        p.put("vnp_TmnCode", "TESTCODE");
        p.put("vnp_TxnRef", "ORD123-1");
        p.put("vnp_Amount", "1000000");
        p.put("vnp_BankCode", "NCB");
        p.put("vnp_PayDate", "20260101110005");
        p.put("vnp_TransactionNo", "14123999");
        p.put("vnp_TransactionType", "02");
        p.put("vnp_TransactionStatus", "05");
        p.put("vnp_OrderInfo", "Hoan tien don hang ORD123");
        p.put("vnp_SecureHash", REFUND_RESPONSE_HASH);
        return p;
    }

    @Test
    void aRefundRequestIsHashedOverItsValuesInVnpaysOrder() {
        Map<String, String> p = refundRequest();
        p.put("vnp_SecureHash", "ignored");   // not one of the signed fields

        assertThat(signer.refundRequestHash(p)).isEqualTo(REFUND_REQUEST_HASH);
    }

    @Test
    void aMissingRefundValueKeepsItsPlaceAsAnEmptyOne() {
        Map<String, String> p = refundRequest();
        p.remove("vnp_TransactionNo");

        assertThat(signer.refundRequestHash(p)).isEqualTo(REFUND_REQUEST_HASH_WITHOUT_TRANSACTION_NO);
    }

    @Test
    void aRefundResponseSignedWithTheSecretIsAccepted_inAnyHexCase() {
        Map<String, String> p = refundResponse();
        assertThat(signer.verifyRefundResponse(p)).isTrue();

        p.put("vnp_SecureHash", REFUND_RESPONSE_HASH.toUpperCase());
        assertThat(signer.verifyRefundResponse(p)).isTrue();
    }

    @Test
    void aChangedRefundResponseOrOneWithoutAHashIsRejected() {
        Map<String, String> changed = refundResponse();
        changed.put("vnp_TransactionStatus", "00");
        assertThat(signer.verifyRefundResponse(changed)).isFalse();

        Map<String, String> unsigned = refundResponse();
        unsigned.remove("vnp_SecureHash");
        assertThat(signer.verifyRefundResponse(unsigned)).isFalse();
    }

    private static Map<String, String> withHash() {
        Map<String, String> p = params();
        p.put("vnp_SecureHash", EXPECTED_HASH);
        return p;
    }
}
