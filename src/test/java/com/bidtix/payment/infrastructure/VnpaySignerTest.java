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
                "2.1.0","pay" ,15);
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

    private static Map<String, String> withHash() {
        Map<String, String> p = params();
        p.put("vnp_SecureHash", EXPECTED_HASH);
        return p;
    }
}
