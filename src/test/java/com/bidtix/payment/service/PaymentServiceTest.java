package com.bidtix.payment.service;

import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.payment.infrastructure.VnpaySigner;
import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The payment page of an order: a PENDING payment attempt is recorded and the URL is signed for it. */
@TestPropertySource(properties = {
        "bidtix.payment.vnpay.tmn-code=TESTCODE",
        "bidtix.payment.vnpay.hash-secret=TESTSECRET"})
class PaymentServiceTest extends PostgresIntegrationTest {

    private static final DateTimeFormatter VNPAY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId VNPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired PaymentService service;
    @Autowired VnpaySigner signer;
    @Autowired JdbcTemplate jdbc;

    /** A customer, and an unpaid order of theirs with this much time left. */
    private record Buyer(UUID publicId, long orderId, String orderCode) {
    }

    @Test
    void theUrlIsSignedAndCarriesTheOrdersPayment() {
        Buyer buyer = order("PENDING_PAYMENT", "30 minutes", 250_000);
        Instant before = Instant.now();

        Map<String, String> url = parse(service.createUrlPayment(buyer.orderCode(), buyer.publicId(), "203.0.113.7"));

        assertThat(signer.verify(url)).as("the signature checks out").isTrue();
        assertThat(url).containsEntry("vnp_TmnCode", "TESTCODE")
                .containsEntry("vnp_Command", "pay")
                .containsEntry("vnp_Version", "2.1.0")
                .containsEntry("vnp_CurrCode", "VND")
                .containsEntry("vnp_Locale", "vn")
                .containsEntry("vnp_OrderType", "other")
                .containsEntry("vnp_IpAddr", "203.0.113.7")
                .containsEntry("vnp_Amount", "25000000");   // VND times 100
        assertThat(url.get("vnp_ReturnUrl")).isEqualTo("http://localhost:8080/api/v1/payments/vnpay/return");
        assertThat(url.get("vnp_OrderInfo")).matches("[A-Za-z0-9 ]+").startsWith("Thanh toan don hang ORDER");

        Map<String, Object> payment = jdbc.queryForMap("select provider, status, amount, txn_ref from payments where order_id = ?",
                buyer.orderId());
        assertThat(payment).containsEntry("provider", "VNPAY").containsEntry("status", "PENDING").containsEntry("amount", 250_000L);
        assertThat(url.get("vnp_TxnRef")).isEqualTo(payment.get("txn_ref")).matches("[A-Za-z0-9]{1,100}");

        // the dates are Vietnam time, as yyyyMMddHHmmss
        Instant created = vietnam(url.get("vnp_CreateDate"));
        assertThat(created).isBetween(before.minusSeconds(2), Instant.now().plusSeconds(2));
        assertThat(vietnam(url.get("vnp_ExpireDate"))).isAfter(created);
    }

    @Test
    void theCreationTimeSentToVnpayIsStoredExactly_forARefundLater() {
        Buyer buyer = order("PENDING_PAYMENT", "30 minutes", 1_000);

        Map<String, String> url = parse(service.createUrlPayment(buyer.orderCode(), buyer.publicId(), "203.0.113.7"));

        Timestamp stored = jdbc.queryForObject("select requested_at from payments where order_id = ?", Timestamp.class,
                buyer.orderId());
        // to the second, with nothing after it: the same value vnp_CreateDate carries
        assertThat(stored.toInstant()).isEqualTo(vietnam(url.get("vnp_CreateDate")));
    }

    @Test
    void thePageClosesAtTheOrdersDeadline_whenThatComesBeforeTheDefaultWindow() {
        Buyer buyer = order("PENDING_PAYMENT", "5 minutes", 1_000);   // expire-minutes is 15
        Instant deadline = jdbc.queryForObject("select expires_at from orders where id = ?", Instant.class, buyer.orderId());

        Map<String, String> url = parse(service.createUrlPayment(buyer.orderCode(), buyer.publicId(), "203.0.113.7"));

        assertThat(vietnam(url.get("vnp_ExpireDate"))).isBeforeOrEqualTo(deadline).isAfter(deadline.minusSeconds(2));
    }

    @Test
    void thePageClosesAfterTheDefaultWindow_whenTheOrderHasMoreTime() {
        Buyer buyer = order("PENDING_PAYMENT", "2 hours", 1_000);

        Map<String, String> url = parse(service.createUrlPayment(buyer.orderCode(), buyer.publicId(), "203.0.113.7"));

        Instant created = vietnam(url.get("vnp_CreateDate"));
        assertThat(vietnam(url.get("vnp_ExpireDate"))).isEqualTo(created.plusSeconds(15 * 60));
    }

    @Test
    void everyCallIsAnotherAttemptWithItsOwnReference() {
        Buyer buyer = order("PENDING_PAYMENT", "30 minutes", 1_000);

        String first = parse(service.createUrlPayment(buyer.orderCode(), buyer.publicId(), "203.0.113.7")).get("vnp_TxnRef");
        String second = parse(service.createUrlPayment(buyer.orderCode(), buyer.publicId(), "203.0.113.7")).get("vnp_TxnRef");

        assertThat(first).isNotEqualTo(second);
        assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, buyer.orderId())).isEqualTo(2);
    }

    @Test
    void theIpv6LoopbackIsSentAsAnIpv4Address() {
        Buyer buyer = order("PENDING_PAYMENT", "30 minutes", 1_000);

        Map<String, String> url = parse(service.createUrlPayment(buyer.orderCode(), buyer.publicId(), "0:0:0:0:0:0:0:1"));

        assertThat(url).containsEntry("vnp_IpAddr", "127.0.0.1");
    }

    @Test
    void anotherCustomersOrder_andAnUnknownCode_areNotFound_andLeaveNothingBehind() {
        Buyer buyer = order("PENDING_PAYMENT", "30 minutes", 1_000);
        Buyer stranger = order("PENDING_PAYMENT", "30 minutes", 1_000);

        assertThatThrownBy(() -> service.createUrlPayment(buyer.orderCode(), stranger.publicId(), "203.0.113.7"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOTFOUND));
        assertThatThrownBy(() -> service.createUrlPayment("ORDER_UNKNOWN", buyer.publicId(), "203.0.113.7"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOTFOUND));
        assertThat(payments(buyer)).isZero();
    }

    @Test
    void anOrderThatIsNotWaitingForPayment_orIsAboutToExpire_isNotPayable_andLeavesNothingBehind() {
        for (Buyer buyer : new Buyer[]{
                order("PAID", "30 minutes", 1_000),
                order("CANCELLED", "30 minutes", 1_000),
                order("PENDING_PAYMENT", "-1 minute", 1_000),     // past its deadline
                order("PENDING_PAYMENT", "30 seconds", 1_000)}) { // less than a minute left
            assertThatThrownBy(() -> service.createUrlPayment(buyer.orderCode(), buyer.publicId(), "203.0.113.7"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_PAYABLE));
            assertThat(payments(buyer)).isZero();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private int payments(Buyer buyer) {
        return jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, buyer.orderId());
    }

    /** The query parameters of the URL, decoded, as VNPAY and the signer see them. */
    private static Map<String, String> parse(String url) {
        Map<String, String> params = new HashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams().forEach((name, values) ->
                params.put(name, URLDecoder.decode(values.get(0), StandardCharsets.UTF_8)));
        return params;
    }

    private static Instant vietnam(String yyyyMMddHHmmss) {
        return LocalDateTime.parse(yyyyMMddHHmmss, VNPAY_TIME).atZone(VNPAY_ZONE).toInstant();
    }

    /** A customer with one order in this status and with this much time left to pay (an SQL interval), inserted by SQL. */
    private Buyer order(String status, String timeLeft, long amount) {
        String run = UUID.randomUUID().toString().replace("-", "");
        UUID publicId = UUID.randomUUID();
        long user = jdbc.queryForObject("insert into users (public_id, email, password_hash, full_name, role) "
                + "values (?, ?, 'hash', 'Payer', 'CUSTOMER') returning id", Long.class, publicId, "pay-" + run + "@test.dev");
        long venue = jdbc.queryForObject("insert into venues (name, address, city) values ('Arena', '1 Street', 'Hanoi') "
                + "returning id", Long.class);
        long event = jdbc.queryForObject("insert into events (organizer_id, venue_id, title, starts_at, ends_at, "
                + "sale_starts_at, sale_ends_at) values (?, ?, 'Concert', now() + interval '30 days', "
                + "now() + interval '31 days', now(), now() + interval '29 days') returning id", Long.class, user, venue);
        String code = "ORDER_" + run.substring(0, 13).toUpperCase();
        long order = jdbc.queryForObject("insert into orders (user_id, event_id, source, status, total_amount, expires_at, "
                + "paid_at, order_code) values (?, ?, 'BOOKING', ?, ?, now() + ?::interval, "
                + "case when ? = 'PAID' then now() end, ?) returning id", Long.class,
                user, event, status, amount, timeLeft, status, code);
        return new Buyer(publicId, order, code);
    }
}
