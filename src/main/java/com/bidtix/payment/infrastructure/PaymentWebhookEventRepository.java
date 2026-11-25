package com.bidtix.payment.infrastructure;

import com.bidtix.payment.domain.PaymentProvider;
import com.bidtix.payment.domain.PaymentWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, Long> {

    /**
     * Stores a callback unless this provider's event is already stored; the first payload stays, a repeat does
     * nothing. {@code ON CONFLICT DO NOTHING} instead of catching the constraint violation, which would doom the
     * transaction. Native, so the provider is passed by name.
     */
    @Modifying
    @Query(value = "insert into payment_webhook_events (provider, provider_event_id, payload) "
            + "values (:provider, :eventId, cast(:payload as jsonb)) "
            + "on conflict (provider, provider_event_id) do nothing", nativeQuery = true)
    void insertIfAbsent(@Param("provider") String provider, @Param("eventId") String eventId,
                       @Param("payload") String payload);

    /** Sets {@code processed_at} of a stored event that has none yet. Check the row count. */
    @Modifying
    @Query("update PaymentWebhookEvent e set e.processedAt = :now "
            + "where e.provider = :provider and e.providerEventId = :eventId and e.processedAt is null")
    int markProcessed(@Param("provider") PaymentProvider provider, @Param("eventId") String eventId,
                      @Param("now") Instant now);
}
