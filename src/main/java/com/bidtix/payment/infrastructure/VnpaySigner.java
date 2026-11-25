package com.bidtix.payment.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Signs payment URLs and checks the signature of VNPAY's callbacks (version 2.1.0, HMAC-SHA512).
 * <p>
 * The text that is signed: every parameter except {@code vnp_SecureHash} and {@code vnp_SecureHashType}, with empty
 * values left out, sorted by name, each written {@code name=value} (name and value URL-encoded, a space as {@code +})
 * and joined with {@code &}. The hash is lowercase hex of HMAC-SHA512 of that text with the hash secret. VNPAY signs its
 * callbacks (IPN and return URL) the same way, so {@link #verify} works on the parameters it sends.
 * <p>
 * The refund API ({@link #refundRequestHash}, {@link #verifyRefundResponse}) signs differently: the VALUES only, not
 * encoded, in an order VNPAY fixes per message, joined with {@code |}; same secret and hash.
 * The query API (querydr: {@link #queryRequestHash}, {@link #verifyQueryResponse}) works the same way with its own fields.
 */
@Component
@RequiredArgsConstructor
public class VnpaySigner {

    static final String SECURE_HASH = "vnp_SecureHash";
    static final String SECURE_HASH_TYPE = "vnp_SecureHashType";
    private static final String HMAC = "HmacSHA512";
    /** The signed values of a refund request, in VNPAY's order. */
    private static final List<String> REFUND_REQUEST_FIELDS = List.of(
            "vnp_RequestId", "vnp_Version", "vnp_Command", "vnp_TmnCode", "vnp_TransactionType", "vnp_TxnRef", "vnp_Amount",
            "vnp_TransactionNo", "vnp_TransactionDate", "vnp_CreateBy", "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo");
    /** The signed values of the answer to a refund request, in VNPAY's order. */
    private static final List<String> REFUND_RESPONSE_FIELDS = List.of(
            "vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message", "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount",
            "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo", "vnp_TransactionType", "vnp_TransactionStatus",
            "vnp_OrderInfo");
    /** The signed values of a querydr request, in VNPAY's order. */
    private static final List<String> QUERY_REQUEST_FIELDS = List.of(
            "vnp_RequestId", "vnp_Version", "vnp_Command", "vnp_TmnCode", "vnp_TxnRef", "vnp_TransactionDate",
            "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo");
    /** The signed values of the answer to querydr: those of a refund answer, then the promotion. */
    private static final List<String> QUERY_RESPONSE_FIELDS = Stream.concat(
            REFUND_RESPONSE_FIELDS.stream(), Stream.of("vnp_PromotionCode", "vnp_PromotionAmount")).toList();

    private final VnpayProperties properties;

    /** The payment URL: {@code payUrl?<sorted, encoded parameters>&vnp_SecureHash=<hash>}. */
    public String signedPaymentUrl(Map<String, String> params) {
        String query = signedText(params);
        return properties.payUrl() + "?" + query + "&" + SECURE_HASH + "=" + hash(query);
    }

    /**
     * True when {@code params} carry a {@code vnp_SecureHash} that matches the other parameters. Anything else (no
     * hash, a wrong one, a changed value) is false. The comparison does not leak where the first difference is.
     */
    public boolean verify(Map<String, String> params) {
        return matches(params.get(SECURE_HASH), () -> signedText(params));
    }

    /**
     * The {@code vnp_SecureHash} to put in a refund request. Only the refund request's fields count, anything else in
     * {@code params} is ignored; a missing one is signed as empty.
     */
    public String refundRequestHash(Map<String, String> params) {
        return hash(joinedValues(REFUND_REQUEST_FIELDS, params));
    }

    /** True when VNPAY's answer to a refund request carries a {@code vnp_SecureHash} that matches its values. */
    public boolean verifyRefundResponse(Map<String, String> response) {
        return matches(response.get(SECURE_HASH), () -> joinedValues(REFUND_RESPONSE_FIELDS, response));
    }

    /**
     * The {@code vnp_SecureHash} to put in a querydr request. Only that request's fields count; a missing one is signed as
     * empty.
     */
    public String queryRequestHash(Map<String, String> params) {
        return hash(joinedValues(QUERY_REQUEST_FIELDS, params));
    }

    /** True when VNPAY's answer to querydr carries a {@code vnp_SecureHash} that matches its values. */
    public boolean verifyQueryResponse(Map<String, String> response) {
        return matches(response.get(SECURE_HASH), () -> joinedValues(QUERY_RESPONSE_FIELDS, response));
    }

    private static String joinedValues(List<String> fields, Map<String, String> params) {
        return fields.stream()
                .map(field -> Objects.toString(params.get(field), ""))
                .collect(Collectors.joining("|"));
    }

    /** Compares in constant time, in any hex case; no hash received means no match. */
    private boolean matches(String received, Supplier<String> signedText) {
        if (received == null || received.isBlank()) {
            return false;
        }
        byte[] expected = hash(signedText.get()).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = received.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    /** The sorted, encoded text that is hashed (and that forms the URL's query before the hash). */
    String signedText(Map<String, String> params) {
        Map<String, String> sorted = new TreeMap<>();
        params.forEach((name, value) -> {
            if (!SECURE_HASH.equals(name) && !SECURE_HASH_TYPE.equals(name) && value != null && !value.isEmpty()) {
                sorted.put(name, value);
            }
        });
        return sorted.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    String hash(String text) {
        String secret = properties.hashSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("bidtix.payment.vnpay.hash-secret is not set (VNP_HASH_SECRET)");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA512 is not available", e);
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
