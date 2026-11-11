package com.bidtix.auth.service;

import com.bidtix.auth.domain.RefreshToken;
import com.bidtix.auth.dtos.AuthTokens;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Plain unit tests: every collaborator that touches the database or crypto is a mock. */
class AuthServiceTest {

    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final RefreshTokenProvider refreshTokenProvider = new RefreshTokenProvider(); // real: cheap and pure
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4); // low cost: fast tests
    private final AuthProperties properties = new AuthProperties(REFRESH_TTL,
            new AuthProperties.RefreshCookie("refresh_token", "/api/v1/auth", true, "Strict"));

    private AuthService service;
    private User alice;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, refreshTokenRepository, refreshTokenProvider,
                jwtUtil, authenticationManager, passwordEncoder, properties);

        alice = new User("alice@example.com", "hash", "Alice", UserRole.CUSTOMER);
        ReflectionTestUtils.setField(alice, "id", 1L);

        when(jwtUtil.generateAccessToken(any())).thenReturn("access-jwt");
    }

    // ---------------------------------------------------------------- register

    @Test
    void register_createsCustomer_withNormalizedEmail_andHashedPassword() {
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.register("  Bob@Example.COM ", "password123", " Bob ");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("bob@example.com");
        assertThat(saved.getValue().getFullName()).isEqualTo("Bob");
        assertThat(saved.getValue().getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void register_existingEmail_isRejected() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertError(() -> service.register("alice@example.com", "password123", "Alice"), ErrorCode.EMAIL_ALREADY_EXISTS);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void register_raceOnSameEmail_isCaughtFromTheUniqueConstraint() {
        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_users_email"));

        assertError(() -> service.register("bob@example.com", "password123", "Bob"), ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    void register_passwordOver72Bytes_isRejected() {
        String tooLong = "€".repeat(30); // 30 characters but 90 bytes

        assertError(() -> service.register("bob@example.com", tooLong, "Bob"), ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(userRepository);
    }

    // ---------------------------------------------------------------- login

    @Test
    void login_issuesTokens_andStoresOnlyTheHashOfTheRefreshToken() {
        when(authenticationManager.authenticate(any())).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(UserPrincipal.from(alice), null, java.util.List.of()));

        Instant before = Instant.now();
        AuthTokens tokens = service.login("alice@example.com", "password123");

        assertThat(tokens.accessToken()).isEqualTo("access-jwt");
        assertThat(tokens.refreshToken()).isNotBlank();

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(1L);
        assertThat(saved.getValue().getTokenHash())
                .isEqualTo(refreshTokenProvider.hashToken(tokens.refreshToken()))
                .isNotEqualTo(tokens.refreshToken());
        assertThat(saved.getValue().getExpiresAt()).isBetween(before.plus(REFRESH_TTL), Instant.now().plus(REFRESH_TTL));
    }

    @Test
    void login_badCredentials_propagate_andNothingIsStored() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> service.login("alice@example.com", "wrong"))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    void login_passwordOver72Bytes_isRejected_withoutCallingTheAuthenticationManager() {
        assertError(() -> service.login("alice@example.com", "€".repeat(30)), ErrorCode.INVALID_CREDENTIALS);
        verifyNoInteractions(authenticationManager);
    }

    // ---------------------------------------------------------------- refresh

    @Test
    void refresh_validToken_rotates_revokesOldThenIssuesNew() {
        String raw = "old-raw-token";
        RefreshToken stored = storedToken(10L, raw, Instant.now().plus(Duration.ofDays(1)), null);
        when(refreshTokenRepository.findByTokenHash(refreshTokenProvider.hashToken(raw))).thenReturn(Optional.of(stored));
        when(refreshTokenRepository.revoke(eq(10L), any())).thenReturn(1);
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));

        AuthTokens tokens = service.refresh(raw);

        assertThat(tokens.accessToken()).isEqualTo("access-jwt");
        assertThat(tokens.refreshToken()).isNotBlank().isNotEqualTo(raw);

        InOrder order = inOrder(refreshTokenRepository);
        order.verify(refreshTokenRepository).revoke(eq(10L), any());
        order.verify(refreshTokenRepository).save(any(RefreshToken.class));
        verify(refreshTokenRepository, never()).revokeAllTokenOfUser(anyLong(), any());
    }

    @Test
    void refresh_missingToken_isRejected_withoutTouchingTheDatabase() {
        assertError(() -> service.refresh(null), ErrorCode.INVALID_REFRESH_TOKEN);
        assertError(() -> service.refresh("   "), ErrorCode.INVALID_REFRESH_TOKEN);
        verifyNoInteractions(refreshTokenRepository, userRepository);
    }

    @Test
    void refresh_unknownToken_isRejected() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertError(() -> service.refresh("who-knows"), ErrorCode.INVALID_REFRESH_TOKEN);
        verify(refreshTokenRepository, never()).revokeAllTokenOfUser(anyLong(), any());
    }

    @Test
    void refresh_expiredToken_isRejected_butDoesNotEndOtherSessions() {
        String raw = "expired";
        RefreshToken stored = storedToken(10L, raw, Instant.now().minusSeconds(1), null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored));

        assertError(() -> service.refresh(raw), ErrorCode.INVALID_REFRESH_TOKEN);
        verify(refreshTokenRepository, never()).revoke(anyLong(), any());
        verify(refreshTokenRepository, never()).revokeAllTokenOfUser(anyLong(), any());
    }

    @Test
    void refresh_reusedToken_isTreatedAsTheft_andEndsEverySession() {
        String raw = "already-used";
        RefreshToken stored = storedToken(10L, raw, Instant.now().plus(Duration.ofDays(1)), Instant.now().minusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored));

        assertError(() -> service.refresh(raw), ErrorCode.INVALID_REFRESH_TOKEN);
        verify(refreshTokenRepository).revokeAllTokenOfUser(eq(1L), any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void refresh_losingTheRaceForTheSameToken_isTreatedAsReuse() {
        String raw = "raced";
        RefreshToken stored = storedToken(10L, raw, Instant.now().plus(Duration.ofDays(1)), null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored));
        when(refreshTokenRepository.revoke(eq(10L), any())).thenReturn(0); // another request spent it first

        assertError(() -> service.refresh(raw), ErrorCode.INVALID_REFRESH_TOKEN);
        verify(refreshTokenRepository).revokeAllTokenOfUser(eq(1L), any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void refresh_lockedUser_cannotRenew_andSessionsAreEnded() {
        ReflectionTestUtils.setField(alice, "status", UserStatus.LOCKED);
        String raw = "locked-user";
        RefreshToken stored = storedToken(10L, raw, Instant.now().plus(Duration.ofDays(1)), null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored));
        when(refreshTokenRepository.revoke(eq(10L), any())).thenReturn(1);
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));

        assertError(() -> service.refresh(raw), ErrorCode.INVALID_REFRESH_TOKEN);
        verify(refreshTokenRepository).revokeAllTokenOfUser(eq(1L), any());
        verify(refreshTokenRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- logout

    @Test
    void logout_revokesTheTokenByItsHash() {
        service.logout("my-token");

        verify(refreshTokenRepository).revokeByTokenHash(eq(refreshTokenProvider.hashToken("my-token")), any());
    }

    @Test
    void logout_withoutToken_doesNothing() {
        service.logout(null);
        service.logout("");

        verifyNoInteractions(refreshTokenRepository);
    }

    // ---------------------------------------------------------------- helpers

    private RefreshToken storedToken(Long id, String raw, Instant expiresAt, Instant revokedAt) {
        RefreshToken token = new RefreshToken(1L, refreshTokenProvider.hashToken(raw), expiresAt);
        ReflectionTestUtils.setField(token, "id", id);
        ReflectionTestUtils.setField(token, "revokedAt", revokedAt);
        return token;
    }

    private static void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
