package com.bidtix.common.idempotency.domain;

import com.bidtix.common.domain.AuditableEntity;
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

@Getter
@Entity
@Table(name = "idempotency_keys")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey extends AuditableEntity {

    /** users.id (user module) */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "idem_key", nullable = false, updatable = false, length = 64)
    private String idemKey;

    @Column(name = "request_method", nullable = false, updatable = false, length = 10)
    private String requestMethod;

    @Column(name = "request_path", nullable = false, updatable = false)
    private String requestPath;

    /** SHA-256 hex of the request body. */
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IdempotencyStatus status = IdempotencyStatus.PROCESSING;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private String responseBody;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    public IdempotencyKey(Long userId, String idemKey, String requestMethod, String requestPath,
                          String requestHash, Instant expiresAt) {
        this.userId = userId;
        this.idemKey = idemKey;
        this.requestMethod = requestMethod;
        this.requestPath = requestPath;
        this.requestHash = requestHash;
        this.expiresAt = expiresAt;
    }
}
