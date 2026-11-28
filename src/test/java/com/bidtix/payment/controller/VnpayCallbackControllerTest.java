package com.bidtix.payment.controller;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.BookingService;
import com.bidtix.booking.service.GeneralAdmissionFixtures;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.payment.infrastructure.VnpaySigner;
import com.bidtix.payment.service.PaymentService;
import com.bidtix.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * VNPAY's IPN over HTTP: no login, the query parameters are bound and verified, and calls are limited per IP. What each
 * kind of confirmation does is covered by {@code VnpayIpnTest}. The rate limit counters live in the shared Redis, so
 * every test calls from its own address.
 */
@TestPropertySource(properties = {
        "bidtix.payment.vnpay.tmn-code=TESTCODE",
        "bidtix.payment.vnpay.hash-secret=TESTSECRET",
        // a small bucket that does not refill during a test
        "bidtix.rate-limit.policies.vnpay-ipn.capacity=3",
        "bidtix.rate-limit.policies.vnpay-ipn.refill-every=1h"})
class VnpayCallbackControllerTest extends PostgresIntegrationTest {

    private static final String IPN = "/api/v1/payments/vnpay/ipn";

    @Autowired WebApplicationContext context;
    @Autowired PaymentService paymentService;
    @Autowired VnpaySigner signer;
    @Autowired BookingService bookingService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    MockMvc mvc;
    private final String ip = "ip-" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void aSignedConfirmationWithoutLogin_paysTheOrder() throws Exception {
        Attempt attempt = attempt();
        Map<String, String> params = signed(attempt);

        mvc.perform(ipn(params))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"RspCode\":\"00\",\"Message\":\"Confirm Success\"}"));

        // each value reached the service from its vnp_ parameter
        Map<String, Object> row = jdbc.queryForMap("select p.status, p.provider_txn_id, o.status as order_status, o.paid_at "
                + "from payments p join orders o on o.id = p.order_id where p.txn_ref = ?", attempt.txnRef());
        assertThat(row).containsEntry("status", "SUCCEEDED").containsEntry("order_status", "PAID")
                .containsEntry("provider_txn_id", params.get("vnp_TransactionNo"));
        assertThat(((Timestamp) row.get("paid_at")).toInstant()).isEqualTo(Instant.parse("2026-11-25T07:30:00Z"));

        mvc.perform(ipn(params)).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("02"));
    }

    @Test
    void aWrongSignature_andNoParametersAtAll_areAnswered97() throws Exception {
        Attempt attempt = attempt();
        Map<String, String> params = signed(attempt);
        params.put("vnp_Amount", "100");   // changed after signing

        mvc.perform(ipn(params)).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("97"));
        mvc.perform(ipn(Map.of())).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("97"));

        assertThat(statusOf(attempt)).isEqualTo("PENDING");
    }

    @Test
    void anAmountThatIsNotANumber_isABadRequest() throws Exception {
        mvc.perform(ipn(Map.of("vnp_Amount", "abc"))).andExpect(status().isBadRequest());
    }

    @Test
    void callsFromOneAddressAreLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(ipn(Map.of())).andExpect(status().isOk());
        }

        mvc.perform(ipn(Map.of()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private record Attempt(long amount, String txnRef) {
    }

    private MockHttpServletRequestBuilder ipn(Map<String, String> params) {
        MockHttpServletRequestBuilder request = get(IPN).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
        params.forEach(request::queryParam);
        return request;
    }

    /** A booked, unpaid order with one payment attempt. */
    private Attempt attempt() {
        Fixture f = GeneralAdmissionFixtures.create(em, tx, 10, 1);
        UUID user = f.userIds().get(0);
        String code = bookingService.bookTicket(new BookingTicketRequest(f.eventId(), f.zoneId(), null, 1), user, UUID.randomUUID().toString()).orderCode();
        String url = paymentService.createUrlPayment(code, user, "203.0.113.7");
        return new Attempt(f.price(), UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("vnp_TxnRef"));
    }

    /** What VNPAY sends for money that was taken, signed. */
    private Map<String, String> signed(Attempt attempt) {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_TmnCode", "TESTCODE");
        params.put("vnp_Amount", String.valueOf(attempt.amount() * 100));
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_PayDate", "20261125143000");   // Vietnam time
        params.put("vnp_OrderInfo", "Thanh toan don hang");   // with spaces
        params.put("vnp_TransactionNo", String.valueOf(ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L)));
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionStatus", "00");
        params.put("vnp_TxnRef", attempt.txnRef());
        String url = signer.signedPaymentUrl(params);
        params.put("vnp_SecureHash", url.substring(url.indexOf("vnp_SecureHash=") + "vnp_SecureHash=".length()));
        return params;
    }

    private String statusOf(Attempt attempt) {
        return jdbc.queryForObject("select status from payments where txn_ref = ?", String.class, attempt.txnRef());
    }
}
