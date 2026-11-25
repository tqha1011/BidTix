package com.bidtix.payment.infrastructure;

import com.bidtix.payment.domain.Payment;
import com.bidtix.payment.domain.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class RefundPaymentJob {
    private final PaymentRepository paymentRepository;
    private final VnpayProperties properties;
    private final VnpaySigner signer;
    private final TransactionTemplate transactionTemplate;
    private static final Integer MAX_REFUND_REQUEST = 100;
    private final Clock clock;
    private static final String VNPAY_SUCCESS = "00";
    /** vnp_ResponseCode: a refund of this transaction was already requested and VNPAY is processing it. */
    private static final String VNPAY_REFUND_ALREADY_REQUESTED = "94";
    /** payments.failure_reason is VARCHAR(255). */
    private static final int FAILURE_REASON_LENGTH = 255;
    /** Starts the part of failure_reason that is about the refund itself. */
    private static final String REFUND_NOTE = "[refund]";
    /** The wait after the 1st, 2nd, ... request that never reached VNPAY; the last one repeats. */
    private static final List<Duration> RETRY_WAITS =
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30));
    private static final int MAX_REFUND_ATTEMPTS = 5;


    /** Pipelines:
     * Transaction 1: get refund_pending payments, mark them as refunding, commit
     * For each refunding payment, call VNPAY's refund API
     *
     * Step 1: get refund_pending payments
     * Step 2: mark it as refunding */
    // every minute by default; "-" turns it off (the tests do). Runs of one instance never overlap.
    @Scheduled(cron = "${bidtix.payment.refund-cron:0 * * * * *}")
    public void run() {
        List<Payment> batch;
        // one moment for the whole run: a payment given back during it waits past this, so no batch picks it up again
        Instant now = Instant.now(clock);
        var client = buildRestClient(properties.refundUrl());
        do{
            batch = transactionTemplate.execute(tx ->
                    paymentRepository.findPaymentWithStatus(PaymentStatus.REFUND_PENDING, now, MAX_REFUND_REQUEST)
                            .stream()
                            .filter(payment -> paymentRepository.markRefundRequested(payment.getId(), now) == 1)
                            .toList());

            for(Payment payment : batch){
                try{
                    requestRefund(client, payment);
                }
                catch(RuntimeException e){
                    // e.g. the database while recording the outcome: the claim stays, the others still go on
                    log.error("Refund of payment {} could not be handled", payment.getTxnRef(), e);
                }
            }
        }
        while (batch.size() == MAX_REFUND_REQUEST);

    }

    /**
     * Sends one claimed refund. The claim is given back (to try again later) only when the request certainly never
     * reached VNPAY; whenever it may have, the claim stays, because sending it again could return the money twice.
     */
    private void requestRefund(RestClient client, Payment payment){
        Map<String,String> params;
        try{
            params = buildVnPayParams(payment, properties.commandRefund());
        }
        catch(RuntimeException e){
            log.error("Could not build the refund request of payment {}", payment.getTxnRef(), e);
            retryLater(payment, "could not build the request: " + e.getClass().getSimpleName());
            return;
        }

        Map<String,String> response;
        try{
            response = client.post()
                    .uri(properties.refundUrl())
                    .body(params)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        }
        catch(RuntimeException e){
            log.error("Refund request for payment {} failed: {}", payment.getTxnRef(), e.toString());
            if(neverSent(e)){
                retryLater(payment, "VNPAY could not be reached");
            } else {
                recordRefundFailure(payment, "Refund outcome unknown, not sent again: " + e.getClass().getSimpleName());
            }
            return;
        }

        if(response == null){
            recordRefundFailure(payment, "Refund outcome unknown, not sent again: empty answer");
            return;
        }
        log.info("Refund request for payment {} returned {}", payment.getTxnRef(), response);
        handleRefundResponse(response, payment);
    }

    /** True only when no connection was made. A read timeout or a broken connection may come after VNPAY got the request. */
    static boolean neverSent(Throwable failure){
        for(Throwable cause = failure; cause != null; cause = cause.getCause()){
            if(cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException){
                return true;
            }
        }
        return false;
    }

    /** Gives the claim back with a growing wait; after the last attempt keeps it, for a person to look at. */
    private void retryLater(Payment payment, String problem){
        int attempt = payment.getRefundAttempts() + 1;   // the entity was read before this claim was counted
        if(attempt >= MAX_REFUND_ATTEMPTS){
            recordRefundFailure(payment, problem + ", gave up after " + attempt + " attempts");
            return;
        }
        Duration wait = RETRY_WAITS.get(Math.min(attempt, RETRY_WAITS.size()) - 1);
        Instant now = clock.instant();
        String reason = failureReason(payment, problem);
        int updated = transactionTemplate.execute(tx ->
                paymentRepository.releaseRefundClaim(payment.getId(), reason, now.plus(wait), now));
        if(updated != 1){
            log.warn("Payment {} no longer waits for its refund, it is not tried again", payment.getTxnRef());
        }
    }

    /**
     * What VNPAY's answer to one refund request means. Three checks, in this order: the signature (and that it is about
     * this payment), {@code vnp_ResponseCode} (was the request processed), and only then {@code vnp_TransactionStatus}
     * (where the refund stands). The payment stays REFUND_PENDING whatever the answer: VNPAY's documentation names no
     * status that clearly says the money is back, so nothing here makes it REFUNDED. A refusal is added to its
     * {@code failure_reason}, for a person to look at; its claim stays, so it is not requested again by itself.
     * Call it outside any transaction.
     */
    void handleRefundResponse(Map<String,String> response, Payment payment){
        if(!signer.verifyRefundResponse(response)){
            String unverified = response.get("vnp_ResponseCode");
            if(response.get("vnp_SecureHash") == null && unverified != null && !VNPAY_SUCCESS.equals(unverified)){
                // VNPAY does not sign its error answers: note the code for a person, and change nothing else
                recordRefundFailure(payment, "Refund failed with an unsigned answer: response code " + unverified);
                return;
            }
            log.error("Refund response for payment {} has an invalid signature: {}", payment.getTxnRef(), response);
            return;
        }
        if(!payment.getTxnRef().equals(response.get("vnp_TxnRef"))){
            log.error("Refund response for payment {} is about another one: {}", payment.getTxnRef(), response);
            return;
        }

        String code = Objects.toString(response.get("vnp_ResponseCode"), "");
        if(VNPAY_REFUND_ALREADY_REQUESTED.equals(code)){
            log.info("Refund of payment {} was requested before and is being processed", payment.getTxnRef());
            return;
        }
        if(!VNPAY_SUCCESS.equals(code)){
            recordRefundFailure(payment, "Refund not processed: response code " + code);
            return;
        }

        String status = Objects.toString(response.get("vnp_TransactionStatus"), "");
        switch (status) {
            // processing at VNPAY, or sent on to the bank
            case "05", "06" -> log.info("Refund of payment {} is being processed (status {})", payment.getTxnRef(), status);
            case "09" -> recordRefundFailure(payment, "Refund rejected: transaction status 09");
            default -> log.warn("Refund of payment {} has an unexpected transaction status '{}'", payment.getTxnRef(), status);
        }
    }

    /** Records a successful refund. */
    private void recordRefundSuccess(Payment payment){
        if(paymentRepository.markRefundSucceeded(payment.getId(), clock.instant()) != 1){
            log.warn("Payment {} no longer waits for its refund, it is not marked as refunded", payment.getTxnRef());
        }
    }

    /** Notes the problem next to the reason the refund is needed, in a transaction of its own. The claim stays. */
    private void recordRefundFailure(Payment payment, String problem){
        String reason = failureReason(payment, problem);
        log.error("Refund of payment {} failed: {}", payment.getTxnRef(), problem);
        int updated = transactionTemplate.execute(tx ->
                paymentRepository.recordRefundFailure(payment.getId(), reason, clock.instant()));
        if(updated != 1){
            log.warn("Payment {} no longer waits for its refund, the failure was not recorded", payment.getTxnRef());
        }
    }

    /** Why the refund is needed, then the latest refund problem (replacing an earlier one), cut to the column's length. */
    private static String failureReason(Payment payment, String problem){
        String why = Objects.toString(payment.getFailureReason(), "");
        int earlier = why.indexOf(REFUND_NOTE);
        if(earlier >= 0){
            why = why.substring(0, earlier);
        }
        String reason = (why.strip() + " " + REFUND_NOTE + " " + problem).strip();
        return reason.substring(0, Math.min(reason.length(), FAILURE_REASON_LENGTH));
    }

    private RestClient buildRestClient(String url){
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build()
        );
        // without it a VNPAY that accepts the connection and never answers would hang the job for good
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(url)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private Map<String,String> buildVnPayParams(Payment payment, String command){
        // VNPAY dates are Vietnam time, as yyyyMMddHHmmss
        var vnpayTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
        var params = new HashMap<String, String>();
        // new for every call (VNPAY refuses a repeat within the day), at most 32 characters
        params.put("vnp_RequestId", UUID.randomUUID().toString().replace("-", ""));
        params.put("vnp_Version", properties.version());
        params.put("vnp_Command", command);
        params.put("vnp_TmnCode", properties.tmnCode());
        params.put("vnp_TransactionType", "02");   // the whole amount
        params.put("vnp_TxnRef", payment.getTxnRef());
        params.put("vnp_Amount", String.valueOf(Math.multiplyExact(payment.getAmount(), 100L)));   // VND times 100
        if (payment.getProviderTxnId() != null) {   // optional
            params.put("vnp_TransactionNo", payment.getProviderTxnId());
        }
        // the vnp_CreateDate of the payment request; findPaymentWithStatus leaves out payments that never stored it
        params.put("vnp_TransactionDate", vnpayTime.format(Objects.requireNonNull(payment.getRequestedAt(),
                () -> "Payment " + payment.getTxnRef() + " has no requested_at, VNPAY cannot refund it")));
        params.put("vnp_CreateBy", "system");
        params.put("vnp_CreateDate", vnpayTime.format(clock.instant()));
        // the address of THIS server, not the customer's
        String serverIp;
        try {
            serverIp = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            serverIp = "127.0.0.1";
        }
        params.put("vnp_IpAddr", serverIp);
        params.put("vnp_OrderInfo", "Hoan tien giao dich " + payment.getTxnRef());
        // last: it covers the values above
        params.put("vnp_SecureHash", signer.refundRequestHash(params));
        return params;
    }
}