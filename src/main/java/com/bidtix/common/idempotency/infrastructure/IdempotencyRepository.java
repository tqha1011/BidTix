package com.bidtix.common.idempotency.infrastructure;

import com.bidtix.common.idempotency.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Every write is one native statement and its row count is checked by the caller. Rows are addressed by
 * {@code (user_id, idem_key)}, the unique key, so no id has to travel back to the filter.
 */
public interface IdempotencyRepository extends JpaRepository<IdempotencyKey, Long> {

    /** What the service needs to know about an existing key. {@code stale}: PROCESSING for longer than the timeout. */
    interface Existing {
        String getRequestMethod();

        String getRequestPath();

        String getRequestHash();

        String getStatus();

        Integer getResponseStatus();

        String getResponseBody();

        boolean getStale();
    }

    /** Removes the key if its time is up, so the same key can start a new request. */
    @Modifying
    @Query(value = "delete from idempotency_keys where user_id = :userId and idem_key = :key and expires_at <= :now",
            nativeQuery = true)
    int deleteExpired(@Param("userId") Long userId, @Param("key") String key, @Param("now") Instant now);

    /**
     * Deletes up to {@code limit} keys whose time is up, the longest expired first, whatever their user and status. Rows
     * another transaction holds are left for later. Fewer than {@code limit} means nothing more can be deleted now.
     */
    @Modifying
    @Query(value = "delete from idempotency_keys where id in (select id from idempotency_keys "
            + "where expires_at <= :now order by expires_at limit :limit for update skip locked)", nativeQuery = true)
    int deleteExpiredBatch(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Claims the key: 1 means this request owns it, 0 means it already exists. A concurrent insert of the same key
     * makes this wait for that transaction, so exactly one caller gets 1.
     */
    @Modifying
    @Query(value = """
            insert into idempotency_keys (user_id, idem_key, request_method, request_path, request_hash, status,
                                          claim_token, expires_at, created_at, updated_at)
            values (:userId, :key, :method, :path, :hash, 'PROCESSING', :token, :expiresAt, :now, :now)
            on conflict (user_id, idem_key) do nothing
            """, nativeQuery = true)
    int claim(@Param("userId") Long userId, @Param("key") String key, @Param("method") String method,
              @Param("path") String path, @Param("hash") String hash, @Param("token") UUID token,
              @Param("expiresAt") Instant expiresAt, @Param("now") Instant now);

    @Query(value = """
            select request_method as "requestMethod", request_path as "requestPath", request_hash as "requestHash",
                   status as "status", response_status as "responseStatus", cast(response_body as text) as "responseBody",
                   (status = 'PROCESSING' and updated_at < :staleBefore) as "stale"
              from idempotency_keys where user_id = :userId and idem_key = :key
            """, nativeQuery = true)
    Optional<Existing> findExisting(@Param("userId") Long userId, @Param("key") String key,
                                    @Param("staleBefore") Instant staleBefore);

    /**
     * Takes over an abandoned PROCESSING key: 1 means this request owns it now (two takers cannot both get 1). The new
     * token shuts the earlier run out of the row.
     */
    @Modifying
    @Query(value = "update idempotency_keys set claim_token = :token, updated_at = :now "
            + "where user_id = :userId and idem_key = :key and status = 'PROCESSING' and updated_at < :staleBefore",
            nativeQuery = true)
    int reclaim(@Param("userId") Long userId, @Param("key") String key, @Param("token") UUID token,
                @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

    @Modifying
    @Query(value = "update idempotency_keys set status = 'COMPLETED', response_status = :status, "
            + "response_body = cast(:body as jsonb), updated_at = :now "
            + "where user_id = :userId and idem_key = :key and claim_token = :token and status = 'PROCESSING'",
            nativeQuery = true)
    int complete(@Param("userId") Long userId, @Param("key") String key, @Param("token") UUID token,
                 @Param("status") int status, @Param("body") String body, @Param("now") Instant now);

    /** A response without a body (for example 204). */
    @Modifying
    @Query(value = "update idempotency_keys set status = 'COMPLETED', response_status = :status, updated_at = :now "
            + "where user_id = :userId and idem_key = :key and claim_token = :token and status = 'PROCESSING'",
            nativeQuery = true)
    int completeWithoutBody(@Param("userId") Long userId, @Param("key") String key, @Param("token") UUID token,
                            @Param("status") int status, @Param("now") Instant now);

    /**
     * Gives the key back after a failed request, so a retry runs again. Only the row of the run holding {@code token},
     * and never a COMPLETED one.
     */
    @Modifying
    @Query(value = "delete from idempotency_keys where user_id = :userId and idem_key = :key and claim_token = :token "
            + "and status = 'PROCESSING'", nativeQuery = true)
    int release(@Param("userId") Long userId, @Param("key") String key, @Param("token") UUID token);
}
