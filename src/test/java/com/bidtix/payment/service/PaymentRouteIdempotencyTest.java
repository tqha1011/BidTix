package com.bidtix.payment.service;

import com.bidtix.common.idempotency.IdempotencyProperties;
import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.AntPathMatcher;

import static org.assertj.core.api.Assertions.assertThat;

/** The payment route is one of the routes {@code IdempotencyFilter} requires an Idempotency-Key for, with the configuration the app really starts with. */
class PaymentRouteIdempotencyTest extends PostgresIntegrationTest {

    @Autowired IdempotencyProperties properties;

    private final AntPathMatcher matcher = new AntPathMatcher();

    private boolean required(String uri) {
        return properties.paths().stream().anyMatch(pattern -> matcher.match(pattern, uri));
    }

    @Test
    void payingAnOrder_needsAnIdempotencyKey() {
        assertThat(required("/api/v1/orders/ORDER_0123456789012/payments")).isTrue();
    }

    @Test
    void theOtherRoutesAreStillCoveredAndUnrelatedOnesAreNot() {
        assertThat(required("/api/v1/orders")).isTrue();
        assertThat(required("/api/v1/auctions/9f1d/bids")).isTrue();
        assertThat(required("/api/v1/orders/ORDER_1")).isFalse();
        assertThat(required("/api/v1/payments/vnpay/ipn")).as("the provider's callback has no key").isFalse();
    }
}
