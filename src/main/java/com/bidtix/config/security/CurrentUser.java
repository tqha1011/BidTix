package com.bidtix.config.security;

import java.security.Principal;
import java.util.UUID;

/**
 * Reads who is calling from the authenticated request. After the JWT filter the principal's name is the
 * token's {@code sub}, which {@link JwtUtil} sets to the user's {@code publicId}.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID publicId(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
