package com.alphaledger.auth.dto;

public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken,
        UserResponse user) {

    public static TokenResponse bearer(String accessToken, long expiresIn, String refreshToken, UserResponse user) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, refreshToken, user);
    }
}
