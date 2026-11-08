package com.bidtix.auth.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings under {@code bidtix.auth.*}. Every value has a default, so nothing is required in
 * application.yaml. Example override for local development over plain http:
 * <pre>
 * bidtix:
 *   auth:
 *     refresh-cookie:
 *       secure: false
 * </pre>
 */
@ConfigurationProperties("bidtix.auth")
public record AuthProperties(
        /* How long a refresh token (and its cookie) stays valid. */
        @DefaultValue("7d") Duration refreshTokenTtl,
        @DefaultValue RefreshCookie refreshCookie) {

    public record RefreshCookie(
            @DefaultValue("refresh_token") String name,
            /* Only requests under this path carry the cookie: refresh and logout, not every API call. */
            @DefaultValue("/api/v1/auth") String path,
            /* "Secure" = browser only sends it over https. Turn off only for local http. */
            @DefaultValue("true") boolean secure,
            /* Strict = never sent on cross-site requests (CSRF protection). */
            @DefaultValue("Strict") String sameSite) {
    }
}
