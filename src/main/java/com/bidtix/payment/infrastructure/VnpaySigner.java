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
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Signs payment URLs and checks the signature of VNPAY's callbacks (version 2.1.0, HMAC-SHA512).
 * <p>
 * The text that is signed: every parameter except {@code vnp_SecureHash} and {@code vnp_SecureHashType}, with empty
 * values left out, sorted by name, each written {@code name=value} (name and value URL-encoded, a space as {@code +})
 * and joined with {@code &}. The hash is lowercase hex of HMAC-SHA512 of that text with the hash secret. VNPAY signs its
 * callbacks (IPN and return URL) the same way, so {@link #verify} works on the parameters it sends.
 */
@Component
@RequiredArgsConstructor
public class VnpaySigner {

    static final String SECURE_HASH = "vnp_SecureHash";
    static final String SECURE_HASH_TYPE = "vnp_SecureHashType";
    private static final String HMAC = "HmacSHA512";

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
        String received = params.get(SECURE_HASH);
        if (received == null || received.isBlank()) {
            return false;
        }
        byte[] expected = hash(signedText(params)).getBytes(StandardCharsets.US_ASCII);
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
