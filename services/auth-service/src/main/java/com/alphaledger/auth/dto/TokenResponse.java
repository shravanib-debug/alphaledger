package com.alphaledger.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Login and refresh response. {@code refreshToken} is present for API clients (body mode) and omitted for browsers
 * (cookie mode), where it travels only in the HttpOnly cookie and script on the page never sees it.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        @JsonInclude(JsonInclude.Include.NON_NULL) String refreshToken,
        UserResponse user) {

    /** The same response without the refresh token, for cookie mode. */
    public TokenResponse withoutRefreshToken() {
        return new TokenResponse(accessToken, tokenType, expiresIn, null, user);
    }

    public static TokenResponse bearer(String accessToken, long expiresIn, String refreshToken, UserResponse user) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, refreshToken, user);
    }
}
