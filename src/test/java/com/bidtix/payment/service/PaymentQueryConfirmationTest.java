package com.bidtix.payment.service;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.BookingService;
import com.bidtix.booking.service.GeneralAdmissionFixtures;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.payment.dtos.VnPayResponse;
import com.bidtix.payment.infrastructure.VnpaySigner;
import com.bidtix.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** What a query's answer does to a payment and its order: the other way, besides the callback, a result arrives. */
@TestPropertySource(properties = {
        "bidtix.payment.vnpay.tmn-code=TESTCODE",
        "bidtix.payment.vnpay.hash-secret=TESTSECRET"})
class PaymentQueryConfirmationTest extends PostgresIntegrationTest {

    private static final String PAY_DATE = "20261125143000";

    @Autowired PaymentService service;
    @Autowired VnpaySigner signer;
    @Autowired BookingService bookingService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private record Attempt(String orderCode, long amount, String txnRef) {
    }

    @Test
    void aPaidAnswer_forAnOrderStillWaiting_issuesTheTickets() {
        Attempt attempt = standingAttempt(2);

        service.confirmQueriedPayment(attempt.txnRef(), numberOf(attempt), PAY_DATE);

        assertPaidOnce(attempt, 2);
    }

    @Test
    void aSecondPaidAnswer_changesNothing() {
        Attempt attempt = standingAttempt(1);

        service.confirmQueriedPayment(attempt.txnRef(), numberOf(attempt), PAY_DATE);
        service.confirmQueriedPayment(attempt.txnRef(), numberOf(attempt), PAY_DATE);

        assertPaidOnce(attempt, 1);
    }

    @Test
    void aFailedAnswer_doesNotTouchAPaymentAlreadyPaid() {
        Attempt attempt = standingAttempt(1);
        service.confirmQueriedPayment(attempt.txnRef(), numberOf(attempt), PAY_DATE);

        service.failQueriedPayment(attempt.txnRef(), "late answer");

        assertPaidOnce(attempt, 1);
    }

    @Test
    void theQueryAndTheCallbackAtTheSameMoment_payTheOrderOnce() throws Exception {
        for (int round = 0; round < 10; round++) {
            Attempt attempt = standingAttempt(1);
            Map<String, String> callback = callback(attempt);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                List<Future<?>> both = List.of(
                        pool.submit(() -> {
                            go.await();
                            service.confirmQueriedPayment(attempt.txnRef(), numberOf(attempt), PAY_DATE);
                            return null;
                        }),
                        pool.submit(() -> {
                            go.await();
                            send(callback);
                            return null;
                        }));
                go.countDown();
                for (Future<?> one : both) {
                    one.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            assertPaidOnce(attempt, 1);
            assertThat(unprocessedCallbacks(attempt)).as("round %s", round).isZero();
        }
    }

    @Test
    void aCallbackThatArrivesAfterTheQuery_withTheSameResult_isMarkedProcessed() {
        Attempt attempt = standingAttempt(1);
        service.confirmQueriedPayment(attempt.txnRef(), numberOf(attempt), PAY_DATE);

        send(callback(attempt));

        assertPaidOnce(attempt, 1);
        assertThat(unprocessedCallbacks(attempt)).isZero();
    }

    @Test
    void aCallbackThatContradictsTheQuery_staysUnprocessed_forAPersonToSee() {
        Attempt attempt = standingAttempt(1);
        service.failQueriedPayment(attempt.txnRef(), "VNPAY query: transaction status 02");

        send(callback(attempt));   // says paid

        assertThat(unprocessedCallbacks(attempt)).isEqualTo(1);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private int unprocessedCallbacks(Attempt attempt) {
        return jdbc.queryForObject("select count(*) from payment_webhook_events where processed_at is null "
                + "and provider_event_id like ?", Integer.class, attempt.txnRef() + ":%");
    }

    private void assertPaidOnce(Attempt attempt, int tickets) {
        assertThat(jdbc.queryForMap("select status, provider_txn_id from payments where txn_ref = ?", attempt.txnRef()))
                .containsEntry("status", "SUCCEEDED").containsEntry("provider_txn_id", numberOf(attempt));
        assertThat(jdbc.queryForObject("select status from orders where order_code = ?", String.class, attempt.orderCode()))
                .isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select count(*) from tickets t join orders o on o.id = t.order_id "
                + "where o.order_code = ?", Integer.class, attempt.orderCode())).isEqualTo(tickets);
    }

    private Attempt standingAttempt(int quantity) {
        Fixture f = GeneralAdmissionFixtures.create(em, tx, 10, 1);
        UUID user = f.userIds().get(0);
        String code = bookingService.bookTicket(new BookingTicketRequest(f.eventId(), f.zoneId(), null, quantity), user)
                .orderCode();
        String url = service.createUrlPayment(code, user, "203.0.113.7");
        String txnRef = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("vnp_TxnRef");
        long amount = jdbc.queryForObject("select total_amount from orders where order_code = ?", Long.class, code);
        return new Attempt(code, amount, txnRef);
    }

    private static String numberOf(Attempt attempt) {
        return "TXN" + attempt.txnRef().substring(0, 16);
    }

    private Map<String, String> callback(Attempt attempt) {
        Map<String, String> params = new HashMap<>(Map.of("vnp_TmnCode", "TESTCODE",
                "vnp_Amount", String.valueOf(attempt.amount() * 100), "vnp_PayDate", PAY_DATE,
                "vnp_TransactionNo", numberOf(attempt), "vnp_ResponseCode", "00", "vnp_TransactionStatus", "00",
                "vnp_TxnRef", attempt.txnRef()));
        String url = signer.signedPaymentUrl(params);
        params.put("vnp_SecureHash", url.substring(url.indexOf("vnp_SecureHash=") + "vnp_SecureHash=".length()));
        return params;
    }

    private void send(Map<String, String> p) {
        service.processVnPayResponse(p, new VnPayResponse(p.get("vnp_TmnCode"), Long.valueOf(p.get("vnp_Amount")), null,
                null, null, p.get("vnp_PayDate"), null, p.get("vnp_TransactionNo"), p.get("vnp_ResponseCode"),
                p.get("vnp_TransactionStatus"), p.get("vnp_TxnRef"), p.get("vnp_SecureHash")));
    }
}
