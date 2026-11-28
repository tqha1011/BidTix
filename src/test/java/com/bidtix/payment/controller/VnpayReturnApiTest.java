package com.bidtix.payment.controller;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.BookingService;
import com.bidtix.payment.infrastructure.VnpaySigner;
import com.bidtix.payment.service.PaymentService;
import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Where the customer's browser comes back to from VNPAY: it shows the state and never changes it. */
@TestPropertySource(properties = {
        "bidtix.payment.vnpay.tmn-code=TESTCODE",
        "bidtix.payment.vnpay.hash-secret=TESTSECRET"})
class VnpayReturnApiTest extends ApiTest {

    private static final String RETURN = "/api/v1/payments/vnpay/return";

    @Autowired PaymentService paymentService;
    @Autowired VnpaySigner signer;
    @Autowired BookingService bookingService;

    private record Attempt(String orderCode, String txnRef) {
    }

    @Test
    void itShowsWhatVnpaySaid_andWhatIsRecorded_withoutChangingAnything() throws Exception {
        Attempt attempt = attempt();

        mvc.perform(back(signed(attempt, "00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCode").value(attempt.orderCode()))
                .andExpect(jsonPath("$.paidAtProvider").value(true))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))   // only the IPN records it
                .andExpect(jsonPath("$.amount").value(300000));

        assertThat(jdbc.queryForObject("select status from payments where txn_ref = ?", String.class, attempt.txnRef()))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status from orders where order_code = ?", String.class, attempt.orderCode()))
                .isEqualTo("PENDING_PAYMENT");

        Map<String, String> params = signed(attempt, "00");
        mvc.perform(get("/api/v1/payments/vnpay/ipn").queryParams(multi(params))).andExpect(status().isOk());
        mvc.perform(back(params)).andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"));
    }

    @Test
    void aCancelledPayment_isShownAsNotPaid() throws Exception {
        mvc.perform(back(signed(attempt(), "24")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paidAtProvider").value(false));
    }

    @Test
    void aWrongSignature_tellsNothing() throws Exception {
        Map<String, String> params = signed(attempt(), "00");
        params.put("vnp_Amount", "100");

        mvc.perform(back(params))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.orderCode").doesNotExist());
    }

    @Test
    void anUnknownReference_isNotFound() throws Exception {
        Attempt attempt = attempt();

        mvc.perform(back(signed(new Attempt(attempt.orderCode(), "nosuchreference"), "00")))
                .andExpect(status().isNotFound());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder back(Map<String, String> params) {
        return get(RETURN).queryParams(multi(params));
    }

    private static org.springframework.util.MultiValueMap<String, String> multi(Map<String, String> params) {
        var map = new org.springframework.util.LinkedMultiValueMap<String, String>();
        params.forEach(map::add);
        return map;
    }

    private Attempt attempt() {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID zone = Catalog.standingZone(jdbc, event, 10, 10);
        UUID me = user("CUSTOMER");
        String code = bookingService.bookTicket(new BookingTicketRequest(event, zone, null, 1), me, UUID.randomUUID().toString()).orderCode();
        String url = paymentService.createUrlPayment(code, me, "203.0.113.7");
        return new Attempt(code, UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("vnp_TxnRef"));
    }

    private Map<String, String> signed(Attempt attempt, String resultCode) {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_TmnCode", "TESTCODE");
        params.put("vnp_Amount", "30000000");
        params.put("vnp_TransactionNo", "R" + attempt.txnRef().substring(0, 10));
        params.put("vnp_ResponseCode", resultCode);
        params.put("vnp_TransactionStatus", resultCode.equals("00") ? "00" : "02");
        params.put("vnp_TxnRef", attempt.txnRef());
        String url = signer.signedPaymentUrl(params);
        params.put("vnp_SecureHash", url.substring(url.indexOf("vnp_SecureHash=") + "vnp_SecureHash=".length()));
        return params;
    }
}
