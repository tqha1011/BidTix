package com.bidtix.auth.dtos;

/**
 * What {@code AuthService} hands back to the controller after login / refresh.
 * The controller sends {@code accessToken} in the body and {@code refreshToken} in a cookie.
 * ({@code refreshToken} is the RAW value; the database only ever stores its hash.)
 */
public record AuthTokens(String accessToken, String refreshToken) {
}
