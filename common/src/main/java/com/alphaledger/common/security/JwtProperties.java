package com.alphaledger.common.security;

import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings shared by auth-service (which signs tokens) and every service that validates them.
 * The secret is a Base64-encoded HMAC-SHA256 key of at least 32 bytes, supplied via the JWT_SECRET
 * environment variable (see infra/.env.example); it is never stored in the config repo.
 */
@ConfigurationProperties("alphaledger.security.jwt")
public record JwtProperties(String issuer, String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {

    public static final String DEFAULT_ISSUER = "alphaledger-auth";
    private static final int MIN_KEY_BYTES = 32;

    public JwtProperties {
        issuer = issuer == null || issuer.isBlank() ? DEFAULT_ISSUER : issuer;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(7) : refreshTokenTtl;
    }

    /** Decodes and checks the signing key; fails fast with a clear message if it is missing or too short. */
    public SecretKey secretKey() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "alphaledger.security.jwt.secret is not set: provide JWT_SECRET (Base64, >= 32 bytes)");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("alphaledger.security.jwt.secret must be Base64-encoded", ex);
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "alphaledger.security.jwt.secret must decode to at least " + MIN_KEY_BYTES + " bytes");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }
}
