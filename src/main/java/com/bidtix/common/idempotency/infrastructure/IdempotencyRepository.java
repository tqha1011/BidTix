package com.bidtix.common.idempotency.infrastructure;

import com.bidtix.common.idempotency.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository extends JpaRepository<IdempotencyKey, String> {
}
