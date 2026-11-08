package com.bidtix.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Internal identity + creation time. The {@code id} is never exposed through the API;
 * entities that are exposed carry their own {@code publicId} (UUID).
 */
@Getter
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        return id != null && id.equals(((BaseEntity) o).id);
    }

    @Override
    public final int hashCode() {
        // constant per class so the hash does not change when the id is assigned on persist
        return Hibernate.getClass(this).hashCode();
    }
}
