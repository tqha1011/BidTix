package com.bidtix.payment.domain;

import com.bidtix.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Getter
@Entity
@Table(name = "payments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends AuditableEntity {

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    /** orders.id (booking module) */
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 20)
    private PaymentProvider provider;

    /** Our reference for this attempt (VNPAY's vnp_TxnRef): the provider's callback names the payment by it. */
    @Column(name = "txn_ref", nullable = false, updatable = false, length = 100)
    private String txnRef;

    /** The provider's own transaction number, known once it confirms the payment. */
    @Column(name = "provider_txn_id", length = 100)
    private String providerTxnId;

    /** VND; must equal the order total. */
    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency = "VND";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "failure_reason")
    private String failureReason;

    /** Raw provider response, stored as JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private String rawResponse;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public Payment(Long orderId, PaymentProvider provider, String txnRef, long amount) {
        this.orderId = orderId;
        this.provider = provider;
        this.txnRef = txnRef;
        this.amount = amount;
    }
}
