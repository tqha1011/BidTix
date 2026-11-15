package com.bidtix.auth.controller;

import com.bidtix.auth.dtos.AccessTokenResponse;
import com.bidtix.auth.dtos.AuthTokens;
import com.bidtix.auth.dtos.LoginRequest;
import com.bidtix.auth.dtos.RegisterRequest;
import com.bidtix.auth.dtos.RegisterResponse;
import com.bidtix.auth.service.AuthService;
import com.bidtix.config.ratelimit.RateLimited;
import com.bidtix.config.security.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * All endpoints are public (whitelisted in SecurityConfiguration). Controllers only translate
 * HTTP to service calls and back; the logic lives in {@link AuthService}.
 * <p>
 * The refresh token is never in a request or response body: it is set with
 * {@code Set-Cookie} and read back from the {@code Cookie} header.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookies refreshTokenCookies;

    @RateLimited(policy = "auth-register")
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        var user = authService.register(request.email(), request.password(), request.fullName());
        return ResponseEntity.status(HttpStatus.CREATED).body(RegisterResponse.from(user));
    }

    @RateLimited(policy = "auth-login")
    @PostMapping("/login")
    public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return tokenResponse(authService.login(request.email(), request.password()));
    }

    /** No body: the refresh token comes from the cookie. A missing cookie becomes a 401 in the service. */
    @PostMapping("/refresh")
    public ResponseEntity<AccessTokenResponse> refresh(HttpServletRequest request) {
        return tokenResponse(authService.refresh(refreshTokenCookies.read(request)));
    }

    /** Always 204 and always clears the cookie, even if the token was already invalid. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        authService.logout(refreshTokenCookies.read(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.clear().toString())
                .build();
    }

    /** Access token in the body, refresh token in the cookie. */
    private ResponseEntity<AccessTokenResponse> tokenResponse(AuthTokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.create(tokens.refreshToken()).toString())
                .body(AccessTokenResponse.bearer(tokens.accessToken(), JwtUtil.ACCESS_TOKEN_TTL.toSeconds()));
    }
}
