package com.bidtix.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** Base for mutable tables, which also have an {@code updated_at} column. */
@Getter
@MappedSuperclass
public abstract class AuditableEntity extends BaseEntity {

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
