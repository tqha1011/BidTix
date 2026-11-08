package com.bidtix.auth.service;

import com.bidtix.config.security.AuthenticationConfig;
import com.bidtix.config.security.CustomUserDetailService;
import com.bidtix.config.security.UserPrincipal;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.domain.UserStatus;
import com.bidtix.user.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticationManagerTest {

    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(4); // low cost: fast tests

    private final UserRepository userRepository = mock(UserRepository.class);
    private AuthenticationManager authenticationManager;
    private User user;

    @BeforeEach
    void setUp() {
        authenticationManager = new AuthenticationConfig().authenticationManager(
                new CustomUserDetailService(userRepository), PASSWORD_ENCODER);

        user = new User("alice@example.com", PASSWORD_ENCODER.encode("s3cret!"), "Alice", UserRole.CUSTOMER);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
    }

    @Test
    void correctCredentials_authenticatesWithRoleAndPublicId() {
        Authentication result = authenticate("alice@example.com", "s3cret!");

        assertThat(result.isAuthenticated()).isTrue();
        // Spring Security 7 adds a FACTOR_PASSWORD authority (MFA support) on top of the user's own
        // authorities. Build JWT "roles" from the principal, not from result.getAuthorities().
        assertThat(result.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "FACTOR_PASSWORD");

        UserPrincipal principal = (UserPrincipal) result.getPrincipal();
        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CUSTOMER");
        assertThat(principal.getPublicId()).isEqualTo(user.getPublicId());
        assertThat(principal.getPassword()).as("credentials erased after login").isNull();
        assertThat(result.getCredentials()).isNull();
    }

    @Test
    void emailIsCaseAndWhitespaceInsensitive() {
        assertThat(authenticate("  Alice@Example.COM ", "s3cret!").isAuthenticated()).isTrue();
    }

    @Test
    void wrongPassword_isRejected() {
        assertThatThrownBy(() -> authenticate("alice@example.com", "wrong"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void unknownEmail_looksTheSameAsWrongPassword() {
        assertThatThrownBy(() -> authenticate("nobody@example.com", "s3cret!"))
                .isExactlyInstanceOf(BadCredentialsException.class);
    }

    @Test
    void lockedAccount_isRejected() {
        ReflectionTestUtils.setField(user, "status", UserStatus.LOCKED);

        assertThatThrownBy(() -> authenticate("alice@example.com", "s3cret!"))
                .isInstanceOf(LockedException.class);
    }

    private Authentication authenticate(String email, String password) {
        return authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, password));
    }
}
