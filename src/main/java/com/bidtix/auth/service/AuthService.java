package com.bidtix.auth.service;

import com.bidtix.auth.domain.RefreshToken;
import com.bidtix.auth.dtos.AuthTokens;
import com.bidtix.auth.dtos.RefreshTokenGenerateResult;
import com.bidtix.auth.infrastructure.AuthProperties;
import com.bidtix.auth.infrastructure.RefreshTokenProvider;
import com.bidtix.auth.infrastructure.RefreshTokenRepository;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.config.security.JwtUtil;
import com.bidtix.config.security.UserPrincipal;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.domain.UserStatus;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

/**
 * Register, login, refresh and logout.
 * <p>
 * Tokens: the ACCESS token is a short-lived JWT, never stored. The REFRESH token is a random
 * string; only its SHA-256 hash is stored, and it is rotated on every use (each refresh revokes
 * the old one and issues a new one).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** BCrypt silently ignores (or rejects) anything past 72 bytes. */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenProvider refreshTokenProvider;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    /**
     * Creates a CUSTOMER account. The role is never taken from the request.
     * No @Transactional on purpose: hashing the password is slow, and we do not want to hold a
     * database connection while it runs. The single insert is its own transaction.
     */
    public User register(String email, String rawPassword, String fullName) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        requireBcryptCompatible(rawPassword);

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = new User(normalizedEmail, passwordEncoder.encode(rawPassword), fullName.trim(), UserRole.CUSTOMER);
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two people registered the same email at the same moment: both passed existsByEmail,
            // the unique constraint on users.email let only one insert through.
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    /**
     * Checks email + password (wrong password, unknown email and locked account all throw an
     * AuthenticationException that the exception handler turns into one generic 401), then
     * issues a token pair. Not @Transactional for the same reason as {@link #register}.
     */
    public AuthTokens login(String email, String rawPassword) {
        if (exceedsBcryptLimit(rawPassword)) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, rawPassword));

        return issueTokens((UserPrincipal) authentication.getPrincipal());
    }

    /**
     * Swaps a valid refresh token for a new token pair (rotation).
     * <p>
     * {@code noRollbackFor}: some failures below must still COMMIT the "revoke everything"
     * update they just made. Without this, throwing the exception would roll it back.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthTokens refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw invalidRefreshToken();
        }

        Instant now = Instant.now();
        RefreshToken stored = refreshTokenRepository
                .findByTokenHash(refreshTokenProvider.hashToken(rawRefreshToken))
                .orElseThrow(AuthService::invalidRefreshToken);

        // This token was already used (or logged out) but someone is presenting it again.
        // Either the thief or the real user holds a stale copy, and we cannot tell which,
        // so end every session of this user.
        if (stored.getRevokedAt() != null) {
            throw reuseDetected(stored.getUserId(), now);
        }

        if (!stored.getExpiresAt().isAfter(now)) {
            throw invalidRefreshToken();
        }

        // Rotation. If two requests carry the same token at once, only one UPDATE returns 1;
        // the other sees 0 and is treated as reuse. A token can never be spent twice.
        if (refreshTokenRepository.revoke(stored.getId(), now) == 0) {
            throw reuseDetected(stored.getUserId(), now);
        }

        User user = userRepository.findById(stored.getUserId()).orElseThrow(AuthService::invalidRefreshToken);
        if (user.getStatus() == UserStatus.LOCKED) {
            // Locking an account must also stop existing sessions from renewing themselves.
            refreshTokenRepository.revokeAllTokenOfUser(user.getId(), now);
            throw invalidRefreshToken();
        }

        return issueTokens(UserPrincipal.from(user));
    }

    /**
     * Revokes this device's refresh token. Safe to call twice, with an unknown token, or with
     * none at all: the end state ("this token no longer works") is the same.
     * The access token cannot be revoked and simply expires within a few minutes.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.revokeByTokenHash(refreshTokenProvider.hashToken(rawRefreshToken), Instant.now());
    }

    private AuthTokens issueTokens(UserPrincipal principal) {
        String accessToken = jwtUtil.generateAccessToken(principal);

        RefreshTokenGenerateResult refreshToken = refreshTokenProvider.generate();
        refreshTokenRepository.save(new RefreshToken(
                principal.getId(),
                refreshToken.hashedToken(),
                Instant.now().plus(properties.refreshTokenTtl())));

        return new AuthTokens(accessToken, refreshToken.rawToken());
    }

    private BusinessException reuseDetected(Long userId, Instant now) {
        log.warn("Refresh token reuse detected, revoking all sessions of user id={}", userId);
        refreshTokenRepository.revokeAllTokenOfUser(userId, now);
        return invalidRefreshToken();
    }

    private static BusinessException invalidRefreshToken() {
        return new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
    }

    private static void requireBcryptCompatible(String rawPassword) {
        if (exceedsBcryptLimit(rawPassword)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Password must be at most 72 bytes long");
        }
    }

    private static boolean exceedsBcryptLimit(String rawPassword) {
        return rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }
}
