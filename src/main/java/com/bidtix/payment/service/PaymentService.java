package com.bidtix.payment.service;

import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.payment.domain.Payment;
import com.bidtix.payment.domain.PaymentProvider;
import com.bidtix.payment.infrastructure.PaymentRepository;
import com.bidtix.payment.infrastructure.VnpayProperties;
import com.bidtix.payment.infrastructure.VnpaySigner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Starts payments with VNPAY. It sees an order only as a {@link PayableOrders.PayableOrder}, so the payment module does not
 * depend on booking.
 */
@Component
@RequiredArgsConstructor
public class PaymentService {

    /** VNPAY wants its dates in Vietnam time (GMT+7), as yyyyMMddHHmmss. */
    private static final ZoneId VNPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VNPAY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** A payment page that would close sooner than this is no use to the customer: the order is treated as not payable. */
    static final Duration MIN_PAYMENT_WINDOW = Duration.ofMinutes(1);

    private final PayableOrders payableOrders;
    private final VnpayProperties properties;
    private final VnpaySigner signer;
    private final PaymentRepository paymentRepository;
    private final Clock clock;

    /**
     * Records a PENDING payment attempt for one of the caller's orders and returns the signed VNPAY page for it. Nothing is
     * sent to VNPAY here: the browser goes to the returned URL, and the result comes back later in the callback, which finds
     * this payment by its {@code txn_ref}. The page closes at the earlier of "now + expire-minutes" and the order's deadline,
     * so nobody can pay an order after it expires. Calling it again makes another attempt.
     *
     * @param clientIp the customer's address, which VNPAY requires
     */
    @Transactional
    public String createUrlPayment(String orderCode, UUID userPublicId, String clientIp) {
        var order = payableOrders.findOwnOrder(orderCode, userPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOTFOUND));

        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofMinutes(properties.expireMinutes()));
        if (order.deadline().isBefore(expiresAt)) {
            expiresAt = order.deadline();
        }
        if (!order.awaitingPayment() || expiresAt.isBefore(now.plus(MIN_PAYMENT_WINDOW))) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE);
        }

        // no dashes: vnp_TxnRef is alphanumeric, at most 100 characters, and must not repeat within the day
        String txnRef = UUID.randomUUID().toString().replace("-", "");
        paymentRepository.saveAndFlush(new Payment(order.id(), PaymentProvider.VNPAY, txnRef, order.amount()));

        // the signer sorts, encodes and skips the empty values, so only the content matters here
        Map<String, String> params = Map.ofEntries(
                Map.entry("vnp_Version", properties.version()),
                Map.entry("vnp_Command", properties.command()),
                Map.entry("vnp_TmnCode", properties.tmnCode()),
                Map.entry("vnp_Amount", String.valueOf(Math.multiplyExact(order.amount(), 100L))),   // VND times 100
                Map.entry("vnp_CurrCode", "VND"),
                Map.entry("vnp_CreateDate", VNPAY_TIME.format(now.atZone(VNPAY_ZONE))),
                Map.entry("vnp_ExpireDate", VNPAY_TIME.format(expiresAt.atZone(VNPAY_ZONE))),
                Map.entry("vnp_TxnRef", txnRef),
                Map.entry("vnp_IpAddr", ipAddress(clientIp)),
                Map.entry("vnp_Locale", "vn"),
                // letters, digits and spaces only: the order code has an underscore
                Map.entry("vnp_OrderInfo", "Thanh toan don hang " + order.code().replaceAll("[^A-Za-z0-9]", "")),
                Map.entry("vnp_OrderType", "other"),
                Map.entry("vnp_ReturnUrl", properties.returnUrl()));
        return signer.signedPaymentUrl(params);
    }

    /** VNPAY wants an address of 7 to 45 characters; the IPv6 loopback some servers report is replaced by the IPv4 one. */
    private static String ipAddress(String clientIp) {
        if (clientIp == null || clientIp.isBlank() || "::1".equals(clientIp) || "0:0:0:0:0:0:0:1".equals(clientIp)) {
            return "127.0.0.1";
        }
        return clientIp;
    }
}
