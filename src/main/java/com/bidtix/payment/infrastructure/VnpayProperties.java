package com.bidtix.payment.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * VNPAY settings ({@code bidtix.payment.vnpay}). {@code tmnCode} and {@code hashSecret} come from the merchant account
 * and must be set through environment variables, never committed: the application starts without them, and the first
 * use of {@link VnpaySigner} fails clearly.
 *
 * @param tmnCode    merchant site code ({@code vnp_TmnCode})
 * @param hashSecret secret used to sign requests and check VNPAY's callbacks
 * @param payUrl     where the customer is sent to pay; the sandbox by default, the production URL is a config change
 * @param returnUrl  where the customer's browser comes back to (shows a result only, never confirms a payment)
 * @param expireMinutes how long the payment page accepts the payment
 *
 */
@ConfigurationProperties(prefix = "bidtix.payment.vnpay")
public record VnpayProperties(
        @DefaultValue("") String tmnCode,
        @DefaultValue("") String hashSecret,
        @DefaultValue("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html") String payUrl,
        @DefaultValue("http://localhost:8080/api/v1/payments/vnpay/return") String returnUrl,
        @DefaultValue("http://localhost:8080/api/v1/payments/vnpay/ipn") String ipnUrl,
        @DefaultValue("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction") String refundUrl,
        @DefaultValue("2.1.0") String version,
        @DefaultValue("pay") String commandPay,
        @DefaultValue("refund") String commandRefund,
        @DefaultValue("querydr") String commandQuery,
        @DefaultValue("15") int expireMinutes) {
}
