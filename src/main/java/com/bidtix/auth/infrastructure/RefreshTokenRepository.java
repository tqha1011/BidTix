package com.bidtix.auth.infrastructure;

import com.bidtix.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Marks ONE token as used/revoked, but only if nobody has done it yet.
     * Returns 1 if this call won, 0 if the token was already revoked (or does not exist).
     * <p>
     * Because the condition is in the UPDATE itself, two requests presenting the same refresh
     * token at the same time cannot both succeed: Postgres makes the second wait, re-checks
     * {@code revoked_at IS NULL}, and updates nothing. Always check the returned count.
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :now " +
            "WHERE rt.id = :id AND rt.revokedAt IS NULL")
    int revoke(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Same as {@link #revoke} but finds the token by the hash of the raw value (used by logout).
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :now " +
            "WHERE rt.tokenHash = :tokenHash AND rt.revokedAt IS NULL")
    void revokeByTokenHash(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /**
     * Logs the user out everywhere. Used when a stolen token is detected.
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :now " +
            "WHERE rt.userId = :userId AND rt.revokedAt IS NULL")
    void revokeAllTokenOfUser(@Param("userId") Long userId, @Param("now") Instant now);
}
