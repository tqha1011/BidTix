package com.bidtix.config.security;

import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.domain.UserStatus;
import lombok.Getter;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Authenticated user produced by the login flow. Used only while handling
 * {@code POST /auth/login}; afterwards every request is authenticated from the JWT,
 * whose {@code sub} is {@link #getPublicId()}.
 */
@Getter
public class UserPrincipal implements UserDetails, CredentialsContainer {

    private final Long id;
    private final UUID publicId;
    private final String email;
    private final UserRole role;
    private final UserStatus status;
    private String passwordHash;

    private UserPrincipal(Long id, UUID publicId, String email, String passwordHash,
                          UserRole role, UserStatus status) {
        this.id = id;
        this.publicId = publicId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
    }

    public static UserPrincipal from(User user) {
        return new UserPrincipal(user.getId(), user.getPublicId(), user.getEmail(),
                user.getPasswordHash(), user.getRole(), user.getStatus());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.LOCKED;
    }

    /** Called by {@code ProviderManager} after a successful authentication. */
    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}
