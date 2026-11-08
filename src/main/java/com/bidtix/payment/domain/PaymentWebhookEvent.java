package com.bidtix.payment.domain;

import com.bidtix.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Inbox for provider webhooks. A duplicate delivery violates
 * {@code UNIQUE (provider, provider_event_id)} and is acknowledged without reprocessing.
 */
@Getter
@Entity
@Table(name = "payment_webhook_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentWebhookEvent extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 20)
    private PaymentProvider provider;

    @Column(name = "provider_event_id", nullable = false, updatable = false, length = 100)
    private String providerEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "processed_at")
    private Instant processedAt;

    public PaymentWebhookEvent(PaymentProvider provider, String providerEventId, String payload) {
        this.provider = provider;
        this.providerEventId = providerEventId;
        this.payload = payload;
    }
}
