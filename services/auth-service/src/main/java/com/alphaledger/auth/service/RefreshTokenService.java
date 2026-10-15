package com.alphaledger.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.auth.domain.RefreshToken;
import com.alphaledger.auth.exception.InvalidRefreshTokenException;
import com.alphaledger.auth.repository.RefreshTokenRepository;
import com.alphaledger.common.security.JwtProperties;

/**
 * Opaque, single-use refresh tokens (default 7 days). Each refresh rotates the token; presenting a token
 * that was already used or revoked revokes every token of that user, since it may have been stolen.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final JwtProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, JwtProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Creates a token for the user and returns the raw value; only its hash is stored. */
    @Transactional
    public String issue(Long userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        repository.save(new RefreshToken(userId, hash(raw), now, now.plus(properties.refreshTokenTtl())));
        return raw;
    }

    /** Validates and revokes the presented token; returns the user ID it belonged to. */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Long consume(String raw) {
        RefreshToken token = repository.findByTokenHash(hash(raw)).orElseThrow(InvalidRefreshTokenException::new);
        if (token.isRevoked()) {
            log.warn("Reuse of a revoked refresh token for user {}; revoking all of the user's tokens",
                    token.getUserId());
            repository.revokeAllForUser(token.getUserId());
            throw new InvalidRefreshTokenException();
        }
        if (token.isExpired(clock.instant())) {
            throw new InvalidRefreshTokenException();
        }
        token.revoke();
        return token.getUserId();
    }

    /** Logout: revokes the token if it exists; unknown tokens are ignored. */
    @Transactional
    public void revoke(String raw) {
        repository.findByTokenHash(hash(raw)).ifPresent(RefreshToken::revoke);
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
