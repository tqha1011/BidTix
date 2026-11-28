package com.bidtix.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the {@code @Scheduled} jobs (auction activation and closing, offer expiry, unpaid-order cancellation,
 * refunds, asking about PENDING payments and deleting expired idempotency keys). They are on
 * unless {@code bidtix.scheduling.enabled=false}, which a test sets when a background job would race with what it stubs.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bidtix.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
