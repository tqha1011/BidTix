package com.bidtix.payment.infrastructure;

import com.bidtix.config.metrics.Metrics;
import com.bidtix.payment.domain.Payment;
import com.bidtix.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentPendingCancelJob {
    /** At most this many payments per run: the scheduled jobs share one thread, so a run has to end soon. */
    private static final int MAX_PER_RUN = 20;
    /**
     * When a payment is asked about, counted from the moment it was made. The first leaves the callback time to arrive.
     * The first four come close enough that two fall between the payment page closing (1 to 15 minutes) and the stock
     * being released ({@code confirmation-grace}, 15 minutes, after that): change them together.
     */
    private static final List<Duration> QUERY_TIMES = List.of(Duration.ofMinutes(3), Duration.ofMinutes(10),
            Duration.ofMinutes(17), Duration.ofMinutes(24), Duration.ofHours(1), Duration.ofHours(24));
    static final int MAX_STATUS_QUERIES = 6;
    /** Kept between two queries of a payment whose planned times have passed (the application was down). */
    private static final Duration MIN_QUERY_GAP = Duration.ofMinutes(5);
    private static final String VNPAY_SUCCESS = "00";
    /** vnp_ResponseCode: VNPAY knows no such transaction, as for a payment page nobody used. */
    private static final String VNPAY_NOT_FOUND = "91";
    /** vnp_TransactionType of the record of the payment itself (02 and 03 are refunds). */
    private static final String VNPAY_PAYMENT_RECORD = "01";
    /** vnp_TransactionStatus: the transaction has not been completed. */
    private static final String VNPAY_TRANSACTION_INCOMPLETE = "01";
    /** vnp_TransactionStatus: the transaction ended in an error. */
    private static final String VNPAY_TRANSACTION_FAILED = "02";

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final VnpayProperties properties;
    private final VnpaySigner signer;
    private final Clock clock;
    private final Metrics metrics;
    private final TransactionTemplate transactionTemplate;

    /**
     * Asks VNPAY (querydr) about payments still PENDING: their callback may never arrive, and then a customer who paid
     * would get neither a ticket nor a refund. Each payment is taken (counted, next query planned) and committed before
     * VNPAY is called, outside any transaction.
     */
    // every minute by default, half a minute after the refund job; "-" turns it off (the tests do)
    @Scheduled(cron = "${bidtix.payment.status-query-cron:30 * * * * *}")
    public void cancelPendingPayments(){
        // one moment for the whole run: a payment taken during it is due again only after this
        Instant now = clock.instant();
        RestClient client = buildRestClient(properties.refundUrl());
        for(int asked = 0; asked < MAX_PER_RUN; asked++){
            // one at a time: when the run stops early, no other payment has used up a query
            Payment payment = takeNext(now);
            if(payment == null){
                return;
            }
            try{
                handleQueryResponse(queryVnpay(payment, client), payment);
            }
            catch(ResourceAccessException e){
                // no answer at all: the others would most likely wait for the same timeout, so they are left for later
                checkFailed(payment, e);
                return;
            }
            catch(RuntimeException e){
                checkFailed(payment, e);
            }
        }
    }

    /** The next due payment, taken in a transaction of its own; null when there is none. */
    private Payment takeNext(Instant now){
        return transactionTemplate.execute(tx -> paymentRepository
                .findPaymentsToQuery(now, now.minus(QUERY_TIMES.getFirst()), MAX_STATUS_QUERIES, 1)
                .stream()
                .filter(payment -> paymentRepository.markStatusQueried(payment.getId(),
                        nextQueryAt(payment.getRequestedAt(), payment.getStatusQueries() + 1, now), now) == 1)
                .findFirst()
                .orElse(null));
    }

    /** When a payment may be asked about again after its {@code queriesMade}-th query. Unused after the last one. */
    static Instant nextQueryAt(Instant requestedAt, int queriesMade, Instant now){
        Instant earliest = now.plus(MIN_QUERY_GAP);
        if(queriesMade >= QUERY_TIMES.size()){
            return earliest;
        }
        Instant planned = requestedAt.plus(QUERY_TIMES.get(queriesMade));
        return planned.isAfter(earliest) ? planned : earliest;
    }

    private void checkFailed(Payment payment, RuntimeException e){
        metrics.jobItemFailed(Metrics.Job.PAYMENT_PENDING);
        log.warn("Could not check pending payment {}: {}", payment.getTxnRef(), e.toString());
    }

    /**
     * What querydr's answer says about the payment. Checked in this order: the signature and the reference,
     * {@code vnp_ResponseCode} (was the query processed), {@code vnp_TransactionType} (is this the payment's record),
     * and only then {@code vnp_TransactionStatus}. 00 (paid, with our amount) and 02 (failed) decide at once. "No
     * payment" (status 01, not completed, or response code 91, no such transaction) cancels the payment only at its last
     * query, a day after it was made. Every other answer leaves it PENDING. These meanings come from VNPAY's
     * documentation and have not been seen on a real answer.
     */
    private void handleQueryResponse(Map<String,String> response, Payment payment){
        if(response == null){
            noUsableAnswer(payment, "empty answer");
            return;
        }
        String code = Objects.toString(response.get("vnp_ResponseCode"), "");
        if(VNPAY_NOT_FOUND.equals(code)){
            // expected for abandoned payment pages, so not a failure. Taken signed or not: error answers come unsigned
            noPayment(payment, "response code " + code);
            return;
        }
        if(!signer.verifyQueryResponse(response)){
            // VNPAY does not sign its error answers: nothing in one is acted on
            noUsableAnswer(payment, "no valid signature, response code " + code);
            return;
        }
        if(!payment.getTxnRef().equals(response.get("vnp_TxnRef"))){
            noUsableAnswer(payment, "the answer is about " + response.get("vnp_TxnRef"));
            return;
        }
        if(!VNPAY_SUCCESS.equals(code)){
            noUsableAnswer(payment, "the query was not processed, response code " + code);
            return;
        }
        String type = Objects.toString(response.get("vnp_TransactionType"), "");
        if(!VNPAY_PAYMENT_RECORD.equals(type)){
            noUsableAnswer(payment, "not a payment record, transaction type '" + type + "'");
            return;
        }

        String status = Objects.toString(response.get("vnp_TransactionStatus"), "");
        switch (status) {
            case VNPAY_SUCCESS -> confirm(response, payment);
            case VNPAY_TRANSACTION_FAILED -> paymentService.failQueriedPayment(payment.getTxnRef(),
                    "VNPAY query: transaction status " + status);
            case VNPAY_TRANSACTION_INCOMPLETE -> noPayment(payment, "transaction status " + status);
            // 04 debited at the bank but not successful, 07 suspected fraud, or unknown: money may have moved
            default -> log.warn("Pending payment {} stays undecided: transaction status '{}'", payment.getTxnRef(),
                    status);
        }
    }

    /**
     * VNPAY shows no payment. While queries remain that is not final, the customer may still be paying; at the last one
     * the payment is cancelled.
     */
    private void noPayment(Payment payment, String answer){
        // the entity was read before this query was counted
        if(payment.getStatusQueries() + 1 < MAX_STATUS_QUERIES){
            log.info("No payment yet for pending payment {}: {}", payment.getTxnRef(), answer);
            return;
        }
        paymentService.cancelQueriedPayment(payment.getTxnRef(), "No payment after the last query: " + answer);
    }

    /** VNPAY has the money: the order takes it, or it is marked for a refund. Never for another amount than ours. */
    private void confirm(Map<String,String> response, Payment payment){
        String expected = String.valueOf(Math.multiplyExact(payment.getAmount(), 100L));   // VND times 100
        if(!expected.equals(response.get("vnp_Amount"))){
            noUsableAnswer(payment, "paid amount " + response.get("vnp_Amount") + " instead of " + expected);
            return;
        }
        paymentService.confirmQueriedPayment(payment.getTxnRef(), response.get("vnp_TransactionNo"),
                response.get("vnp_PayDate"));
    }

    private void noUsableAnswer(Payment payment, String problem){
        metrics.jobItemFailed(Metrics.Job.PAYMENT_PENDING);
        log.warn("Query of pending payment {} gave no usable answer: {}", payment.getTxnRef(), problem);
    }

    private Map<String,String> queryVnpay(Payment payment, RestClient client){
        return client.post()
                .uri(properties.refundUrl())
                .body(buildVnPayParams(payment, properties.commandQuery()))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
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
        boolean refund = properties.commandRefund().equals(command);
        if(!refund && !properties.commandQuery().equals(command)){
            throw new IllegalArgumentException("Not a VNPAY merchant API command: " + command);
        }
        // VNPAY dates are Vietnam time, as yyyyMMddHHmmss
        var vnpayTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
        var params = new HashMap<String, String>();
        // new for every call (VNPAY refuses a repeat within the day), at most 32 characters
        params.put("vnp_RequestId", UUID.randomUUID().toString().replace("-", ""));
        params.put("vnp_Version", properties.version());
        params.put("vnp_Command", command);
        params.put("vnp_TmnCode", properties.tmnCode());
        params.put("vnp_TxnRef", payment.getTxnRef());
        if (refund) {
            params.put("vnp_TransactionType", "02");   // the whole amount
            params.put("vnp_Amount", String.valueOf(Math.multiplyExact(payment.getAmount(), 100L)));   // VND times 100
            if (payment.getProviderTxnId() != null) {   // optional
                params.put("vnp_TransactionNo", payment.getProviderTxnId());
            }
            params.put("vnp_CreateBy", "system");
        }
        // the vnp_CreateDate of the payment request; findPaymentWithStatus leaves out payments that never stored it
        params.put("vnp_TransactionDate", vnpayTime.format(Objects.requireNonNull(payment.getRequestedAt(),
                () -> "Payment " + payment.getTxnRef() + " has no requested_at, VNPAY cannot refund it")));
        params.put("vnp_CreateDate", vnpayTime.format(clock.instant()));
        // the address of THIS server, not the customer's
        String serverIp;
        try {
            serverIp = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            serverIp = "127.0.0.1";
        }
        params.put("vnp_IpAddr", serverIp);
        params.put("vnp_OrderInfo", (refund ? "Hoan tien giao dich " : "Truy van giao dich ") + payment.getTxnRef());
        // last: it covers the values above
        params.put("vnp_SecureHash", refund ? signer.refundRequestHash(params) : signer.queryRequestHash(params));
        return params;
    }
}
