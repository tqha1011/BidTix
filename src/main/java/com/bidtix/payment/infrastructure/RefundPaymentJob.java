package com.bidtix.payment.infrastructure;

import com.bidtix.payment.domain.Payment;
import com.bidtix.payment.domain.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
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


    /** Pipelines:
     * Transaction 1: get refund_pending payments, mark them as refunding, commit
     * For each refunding payment, call VNPAY's refund API
     *
     * Step 1: get refund_pending payments
     * Step 2: mark it as refunding */
    public void run() {
        List<Payment> batch;
        Instant now = Instant.now(clock);
        var client = buildRestClient();
        do{
            batch = transactionTemplate.execute(tx ->
                    paymentRepository.findPaymentWithStatus(PaymentStatus.REFUND_PENDING, MAX_REFUND_REQUEST)
                            .stream()
                            .filter(payment -> paymentRepository.markRefundRequested(payment.getId(), now) == 1)
                            .toList());

            for(Payment payment : batch){

            }
        }
        while (batch.size() == MAX_REFUND_REQUEST);

    }

    private RestClient buildRestClient(){
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
                .baseUrl(properties.refundUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private Map<String,String> buildRefundParams(Payment payment){
        // VNPAY dates are Vietnam time, as yyyyMMddHHmmss
        var vnpayTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
        var params = new HashMap<String, String>();
        // new for every call (VNPAY refuses a repeat within the day), at most 32 characters
        params.put("vnp_RequestId", UUID.randomUUID().toString().replace("-", ""));
        params.put("vnp_Version", properties.version());
        params.put("vnp_Command", properties.commandRefund());
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