package com.bidtix.auth.dtos;

/**
 * Body of login / refresh. The refresh token is NOT here: it only travels in an HttpOnly cookie,
 * so JavaScript on the page can never read it.
 */
public record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) {

    public static AccessTokenResponse bearer(String accessToken, long expiresInSeconds) {
        return new AccessTokenResponse(accessToken, "Bearer", expiresInSeconds);
    }
}
