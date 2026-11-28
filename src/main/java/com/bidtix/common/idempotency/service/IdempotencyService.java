package com.bidtix.common.idempotency.service;

import com.bidtix.common.idempotency.IdempotencyProperties;
import com.bidtix.common.idempotency.infrastructure.IdempotencyRepository;
import com.bidtix.common.idempotency.infrastructure.IdempotencyRepository.Existing;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The state of an {@code Idempotency-Key}, kept in PostgreSQL ({@code idempotency_keys}). Every method is its own short
 * transaction: the claim must be committed before the request runs, or a duplicate could not see it.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    /** How a request relates to its key. */
    public enum Outcome {
        /** First time (or an abandoned or expired key): run the request, then {@code complete} or {@code release}. */
        PROCEED,
        /** Same request already finished: send the stored response again. */
        REPLAY,
        /** Same key, but another method, path or body. */
        KEY_REUSED,
        /** Same request is still running. */
        IN_PROGRESS
    }

    /** {@code responseStatus} and {@code responseBody} are only set for {@code REPLAY}; the body may be null. */
    public record Claim(Outcome outcome, Integer responseStatus, String responseBody) {
        static Claim of(Outcome outcome) {
            return new Claim(outcome, null, null);
        }
    }

    private static final int ATTEMPTS = 3;

    private final IdempotencyRepository repository;
    private final UserRepository userRepository;
    private final IdempotencyProperties properties;
    private final Clock clock;

    /** The users.id of the token's subject, which is the user's public id. */
    @Transactional(readOnly = true)
    public Long userId(UUID userPublicId) {
        return userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND)).getId();
    }

    @Transactional
    public Claim claim(Long userId, String key, String method, String path, String requestHash) {
        Instant now = clock.instant();
        Instant staleBefore = now.minus(properties.processingTimeout());
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            repository.deleteExpired(userId, key, now);
            if (repository.claim(userId, key, method, path, requestHash, now.plus(properties.ttl()), now) == 1) {
                return Claim.of(Outcome.PROCEED);
            }
            Existing existing = repository.findExisting(userId, key, staleBefore).orElse(null);
            if (existing == null) {
                continue; // released or expired between our insert and our read: try again
            }
            if (!existing.getRequestMethod().equals(method) || !existing.getRequestPath().equals(path)
                    || !existing.getRequestHash().equals(requestHash)) {
                return Claim.of(Outcome.KEY_REUSED);
            }
            if ("COMPLETED".equals(existing.getStatus())) {
                return new Claim(Outcome.REPLAY, existing.getResponseStatus(), existing.getResponseBody());
            }
            if (existing.getStale() && repository.reclaim(userId, key, now, staleBefore) == 1) {
                return Claim.of(Outcome.PROCEED);
            }
            return Claim.of(Outcome.IN_PROGRESS);
        }
        return Claim.of(Outcome.IN_PROGRESS);
    }

    /** Stores the response of a request that this caller claimed. A null or empty body is stored as "no body". */
    @Transactional
    public void complete(Long userId, String key, int responseStatus, String responseBody) {
        Instant now = clock.instant();
        int rows = responseBody == null || responseBody.isBlank()
                ? repository.completeWithoutBody(userId, key, responseStatus, now)
                : repository.complete(userId, key, responseStatus, responseBody, now);
        if (rows != 1) {
            throw new IllegalStateException("Idempotency key " + key + " was not PROCESSING when it was completed");
        }
    }

    /** Deletes up to {@code limit} expired keys of any user and returns how many; see {@code IdempotencyCleanupJob}. */
    @Transactional
    public int deleteExpiredBatch(int limit) {
        return repository.deleteExpiredBatch(clock.instant(), limit);
    }

    /** Frees a claimed key after a failure, so the client can retry it. */
    @Transactional
    public void release(Long userId, String key) {
        repository.release(userId, key);
    }
}
