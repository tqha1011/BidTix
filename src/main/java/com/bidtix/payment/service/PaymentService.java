package com.bidtix.payment.service;

import com.bidtix.config.metrics.Metrics;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.payment.domain.Payment;
import com.bidtix.payment.domain.PaymentProvider;
import com.bidtix.payment.domain.PaymentStatus;
import com.bidtix.payment.dtos.PaymentReturnResponse;
import com.bidtix.payment.dtos.TransactionResponse;
import com.bidtix.payment.dtos.VnPayResponse;
import com.bidtix.payment.infrastructure.PaymentRepository;
import com.bidtix.payment.infrastructure.PaymentWebhookEventRepository;
import com.bidtix.payment.infrastructure.VnpayProperties;
import com.bidtix.payment.infrastructure.VnpaySigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Starts payments with VNPAY and records what VNPAY says about them. It sees an order only as a {@link PayableOrders.PayableOrder}, so the payment module does not
 * depend on booking.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    /** VNPAY wants its dates in Vietnam time (GMT+7), as yyyyMMddHHmmss. */
    private static final ZoneId VNPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VNPAY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** The value of both {@code vnp_ResponseCode} and {@code vnp_TransactionStatus} for money that was taken. */
    private static final String VNPAY_SUCCESS = "00";
    /** A payment page that would close sooner than this is no use to the customer: the order is treated as not payable. */
    static final Duration MIN_PAYMENT_WINDOW = Duration.ofMinutes(1);

    private final PayableOrders payableOrders;
    private final VnpayProperties properties;
    private final VnpaySigner signer;
    private final PaymentRepository paymentRepository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final JsonMapper jsonMapper;
    private final Metrics metrics;

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

        // whole seconds: what vnp_CreateDate can carry, so the stored value is exactly the one sent (a fraction could even
        // be rounded up to the next second by the database)
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(Duration.ofMinutes(properties.expireMinutes()));
        if (order.deadline().isBefore(expiresAt)) {
            expiresAt = order.deadline();
        }
        if (!order.awaitingPayment() || expiresAt.isBefore(now.plus(MIN_PAYMENT_WINDOW))) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE);
        }

        // no dashes: vnp_TxnRef is alphanumeric, at most 100 characters, and must not repeat within the day
        String txnRef = UUID.randomUUID().toString().replace("-", "");
        paymentRepository.saveAndFlush(new Payment(order.id(), PaymentProvider.VNPAY, txnRef, order.amount(), now));

        // the signer sorts, encodes and skips the empty values, so only the content matters here
        Map<String, String> params = Map.ofEntries(
                Map.entry("vnp_Version", properties.version()),
                Map.entry("vnp_Command", properties.commandPay()),
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

    /**
     * Handles VNPAY's confirmation of one payment attempt (the IPN) and answers in VNPAY's own codes. It never throws:
     * VNPAY stops after {@code 00} or {@code 02} and sends the confirmation again after anything else.
     * <ul>
     * <li>{@code 97}: the signature is wrong. Nothing else is looked at.</li>
     * <li>{@code 01}: no payment has this reference. {@code 04}: the amount is not the payment's amount.</li>
     * <li>{@code 02}: the payment is not PENDING any more, so an earlier delivery already decided it.</li>
     * <li>{@code 00}: recorded now. A failed transaction makes the payment FAILED and leaves the order payable. A
     * successful one pays the order (SUCCEEDED) or, when the order cannot take the money any more, makes the payment
     * REFUND_PENDING. Nothing is refunded here.</li>
     * <li>{@code 99}: an unexpected error; no payment or order was changed.</li>
     * </ul>
     * Every callback with a valid signature is first stored in {@code payment_webhook_events} (one row per event, in a
     * transaction of its own, so it is kept when processing fails), and gets {@code processed_at} only in the transaction
     * that records its result ({@code 00}). A stored event is therefore NOT proof that it was handled: whether to process
     * is decided by the payment's status alone, never by the row being there.
     * <p>
     * Not one {@code @Transactional} method on purpose: the event outlives a failure, and when
     * {@link PayableOrders#applyPayment} throws, its transaction has to roll back and the refund is recorded in another.
     *
     * @param params   every parameter of the request, as received: the signature covers all of them
     * @param response the same parameters, bound
     */
    public TransactionResponse processVnPayResponse(Map<String, String> params, VnPayResponse response) {
        TransactionResponse answer = handleVnPayResponse(params, response);
        metrics.vnpayIpnAnswered(answer.RspCode());   // every transaction of the confirmation has ended
        return answer;
    }

    private TransactionResponse handleVnPayResponse(Map<String, String> params, VnPayResponse response) {
        if (!signer.verify(params)) {
            return TransactionResponse.create("97", "Invalid signature");
        }
        try {
            storeEvent(params, response);
            try {
                return transactionTemplate.execute(tx -> confirm(response, tx));
            } catch (BusinessException e) {
                if (e.getErrorCode() != ErrorCode.ORDER_NOT_PAYABLE) {
                    throw e;
                }
                // rolled back as a whole, but VNPAY has the money
                return transactionTemplate.execute(tx -> refundLater(response, e.getMessage(), tx));
            }
        } catch (RuntimeException e) {
            log.error("Could not process the VNPAY confirmation of payment {}", response.txnRef(), e);
            return TransactionResponse.create("99", "Unknown error");
        }
    }

    /**
     * Reads the state of a payment for the page the customer's browser comes back to. The parameters must carry VNPAY's
     * signature, so only somebody who was sent there with them learns the order code. It changes nothing: the browser is
     * not a trustworthy messenger, only {@link #processVnPayResponse} records a result.
     */
    @Transactional(readOnly = true)
    public PaymentReturnResponse readReturn(Map<String, String> params, VnPayResponse response) {
        if (!signer.verify(params)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Invalid signature");
        }
        Payment payment = paymentRepository.findByProviderAndTxnRef(PaymentProvider.VNPAY, response.txnRef())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOTFOUND));
        var order = payableOrders.findOrder(payment.getOrderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOTFOUND));
        boolean paid = VNPAY_SUCCESS.equals(response.responseCode()) && VNPAY_SUCCESS.equals(response.transactionStatus());
        return new PaymentReturnResponse(order.code(), payment.getAmount(), paid, payment.getStatus());
    }

    /** Its own transaction, committed before the processing starts. A repeated delivery adds nothing. */
    private void storeEvent(Map<String, String> params, VnPayResponse response) {
        String payload = jsonMapper.writeValueAsString(params);
        transactionTemplate.executeWithoutResult(tx -> webhookEventRepository
                .insertIfAbsent(PaymentProvider.VNPAY.name(), eventId(response), payload));
    }

    /**
     * VNPAY sends no event id. One payment attempt has one outcome, so its reference and the transaction status name the
     * event (the transaction number will not do: failed transactions need not have their own).
     */
    private static String eventId(VnPayResponse response) {
        return response.txnRef() + ":" + response.transactionStatus();
    }

    /** One transaction: the payment's new status and what happens to the order commit or roll back together. */
    private TransactionResponse confirm(VnPayResponse response, TransactionStatus tx) {
        Payment payment = paymentRepository.findByProviderAndTxnRef(PaymentProvider.VNPAY, response.txnRef())
                .orElse(null);
        if (payment == null) {
            // the signature is ours, so we should know this reference: a lost row, or another environment's payment
            log.error("VNPAY confirmed an unknown payment: txnRef={}, transactionNo={}", response.txnRef(),
                    response.transactionNo());
            return TransactionResponse.create("01", "Order not found");
        }
        if (response.amount() == null || response.amount() != Math.multiplyExact(payment.getAmount(), 100L)) {
            log.error("VNPAY amount {} (VND x 100) does not match payment {} of {} VND", response.amount(), payment.getTxnRef(),
                    payment.getAmount());
            return TransactionResponse.create("04", "Invalid amount");
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return alreadyConfirmed();
        }

        if (!VNPAY_SUCCESS.equals(response.responseCode()) || !VNPAY_SUCCESS.equals(response.transactionStatus())) {
            // no transaction number: VNPAY may send the same placeholder for every failure, and the column is unique
            return record(payment, response, PaymentStatus.FAILED, null, "VNPAY response code "
                    + response.responseCode() + ", transaction status " + response.transactionStatus(), tx);
        }

        String transactionNo = response.transactionNo();
        return switch (payableOrders.applyPayment(payment.getOrderId(), paidAt(response.payDate()))) {
            case APPLIED -> record(payment, response, PaymentStatus.SUCCEEDED, transactionNo, null, tx);
            case ALREADY_PAID -> record(payment, response, PaymentStatus.REFUND_PENDING, transactionNo,
                    "Another payment already paid the order", tx);
            case NOT_PAYABLE -> record(payment, response, PaymentStatus.REFUND_PENDING, transactionNo,
                    "The order expired or was cancelled", tx);
        };
    }

    /** The order refused the money by throwing: record only that it has to be given back. */
    private TransactionResponse refundLater(VnPayResponse response, String reason, TransactionStatus tx) {
        Payment payment = paymentRepository.findByProviderAndTxnRef(PaymentProvider.VNPAY, response.txnRef())
                .orElseThrow();
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return alreadyConfirmed();
        }
        return record(payment, response, PaymentStatus.REFUND_PENDING, response.transactionNo(), reason, tx);
    }

    /**
     * PENDING -> {@code status} with one conditional UPDATE, and the stored event is marked processed with it. 0 payment
     * rows means another delivery of the same confirmation got there first: everything this transaction did is rolled back.
     */
    private TransactionResponse record(Payment payment, VnPayResponse response, PaymentStatus status,
                                       String transactionNo, String failureReason, TransactionStatus tx) {
        int updated = paymentRepository.updatePaymentStatusByTxnRef(payment.getTxnRef(), transactionNo, status,
                failureReason, payment.getVersion(), clock.instant());
        if (updated != 1) {
            tx.setRollbackOnly();
            return alreadyConfirmed();
        }
        if (webhookEventRepository.markProcessed(PaymentProvider.VNPAY, eventId(response), clock.instant()) != 1) {
            // stored just before, and only this transaction may mark it: anything else is a bug, so undo it all
            throw new IllegalStateException("No unprocessed webhook event " + eventId(response));
        }
        if (status == PaymentStatus.REFUND_PENDING) {
            log.warn("Payment {} has to be refunded: {}", payment.getTxnRef(), failureReason);
        }
        return TransactionResponse.create("00", "Confirm Success");
    }

    private static TransactionResponse alreadyConfirmed() {
        return TransactionResponse.create("02", "Order already confirmed");
    }

    /** VNPAY wants an address of 7 to 45 characters; the IPv6 loopback some servers report is replaced by the IPv4 one. */
    private static String ipAddress(String clientIp) {
        if (clientIp == null || clientIp.isBlank() || "::1".equals(clientIp) || "0:0:0:0:0:0:0:1".equals(clientIp)) {
            return "127.0.0.1";
        }
        return clientIp;
    }

    /** {@code vnp_PayDate} is Vietnam time; it is optional, and without it the payment counts as made now. */
    private Instant paidAt(String payDate) {
        if (payDate == null || payDate.isBlank()) {
            return clock.instant();
        }
        return LocalDateTime.parse(payDate, VNPAY_TIME)
                .atZone(VNPAY_ZONE)
                .toInstant();
    }
}
