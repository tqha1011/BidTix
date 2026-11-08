package com.bidtix.auth.controller;

import com.bidtix.auth.infrastructure.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.time.Duration;

/** Builds, clears and reads the refresh-token cookie, so the cookie rules live in one place. */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookies {

    private final AuthProperties properties;

    /** The cookie that carries a freshly issued refresh token to the browser. */
    public ResponseCookie create(String rawToken) {
        return base(rawToken)
                .maxAge(properties.refreshTokenTtl())
                .build();
    }

    /** An empty, already-expired cookie with the same name and path: tells the browser to delete it. */
    public ResponseCookie clear() {
        return base("")
                .maxAge(Duration.ZERO)
                .build();
    }

    /** The raw refresh token sent by the browser, or {@code null} if there is no such cookie. */
    public String read(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, properties.refreshCookie().name());
        return cookie == null ? null : cookie.getValue();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        AuthProperties.RefreshCookie settings = properties.refreshCookie();
        return ResponseCookie.from(settings.name(), value)
                .httpOnly(true)                   // JavaScript cannot read it (blocks theft via XSS)
                .secure(settings.secure())        // https only
                .sameSite(settings.sameSite())    // not sent on cross-site requests (blocks CSRF)
                .path(settings.path());           // only sent to /api/v1/auth/**
    }
}
