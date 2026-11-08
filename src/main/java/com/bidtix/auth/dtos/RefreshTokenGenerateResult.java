package com.bidtix.auth.dtos;

public record RefreshTokenGenerateResult(String rawToken, String hashedToken) {
    public static RefreshTokenGenerateResult of(String rawToken, String hashedToken) {
        return new RefreshTokenGenerateResult(rawToken, hashedToken);
    }
}
