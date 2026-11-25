package com.bidtix.payment.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Payment settings that do not depend on the provider ({@code bidtix.payment}).
 *
 * @param confirmationGrace how long after its deadline an order with a PENDING payment is still kept, waiting for the
 *                          provider's callback; zero turns the grace period off
 */
@ConfigurationProperties(prefix = "bidtix.payment")
public record PaymentProperties(@DefaultValue("10m") Duration confirmationGrace) {

    public PaymentProperties {
        if (confirmationGrace.isNegative()) {
            throw new IllegalArgumentException("bidtix.payment.confirmation-grace must not be negative");
        }
    }
}
