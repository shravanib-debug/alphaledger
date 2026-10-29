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

import com.alphaledger.auth.config.SessionProperties;
import com.alphaledger.auth.domain.RefreshToken;
import com.alphaledger.auth.domain.RefreshToken.RevokedReason;
import com.alphaledger.auth.exception.InvalidRefreshTokenException;
import com.alphaledger.auth.repository.RefreshTokenRepository;
import com.alphaledger.common.security.JwtProperties;

/**
 * Opaque, single-use refresh tokens (default 7 days). Each refresh rotates the token; presenting a token that was
 * already used or revoked revokes every token of that user, since it may have been stolen (docs/auth-session.md).
 * <p>
 * Concurrency: the presented token is read with a row lock, so two refreshes of one token never both rotate it; the
 * second one sees the rotation and is treated as reuse.
 * <p>
 * The one exception is a <em>replay</em>: the same request retried after its response was lost. It must carry the
 * same {@code Idempotency-Key} as the rotation (stored only as a hash), arrive within the replay window, and the
 * successor must still be unused. The replay supersedes that unused successor with a new one, so at most one token of
 * the chain is ever valid. A token presented without the key, with another key, after the window, or after its
 * successor was used is reuse.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final JwtProperties properties;
    private final SessionProperties session;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, JwtProperties properties, SessionProperties session,
                               Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.session = session;
        this.clock = clock;
    }

    /** A newly issued refresh token: its row ID, owner, raw value (returned once, never stored) and expiry. */
    public record Rotation(Long tokenId, Long userId, String refreshToken, Instant expiresAt) {
    }

    /** Creates a token for the user and returns the raw value; only its hash is stored. */
    @Transactional
    public Rotation issue(Long userId) {
        String raw = newRawToken();
        Instant now = clock.instant();
        RefreshToken token = repository.saveAndFlush(new RefreshToken(userId, hash(raw), now,
                now.plus(properties.refreshTokenTtl())));
        return new Rotation(token.getId(), userId, raw, token.getExpiresAt());
    }

    /**
     * Spends the presented token and returns its successor. {@code idempotencyKey} (optional) identifies this refresh
     * attempt, so a retry of the same attempt after a lost response can be recognised.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String raw, String idempotencyKey) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        Instant now = clock.instant();
        RefreshToken token = repository.lockByTokenHash(hash(raw)).orElseThrow(InvalidRefreshTokenException::new);
        String keyHash = idempotencyKey == null || idempotencyKey.isBlank() ? null : hash(idempotencyKey);

        if (!token.isRevoked()) {
            if (token.isExpired(now)) {
                throw new InvalidRefreshTokenException();
            }
            Rotation next = issue(token.getUserId());
            token.rotate(next.tokenId(), keyHash, now);
            return next;
        }

        if (token.isReplayOf(keyHash, now, session.replayWindow())) {
            RefreshToken successor = repository.lockById(token.getReplacedBy()).orElse(null);
            if (successor != null && successor.isActive(now)) {
                Rotation next = issue(token.getUserId());
                successor.revoke(RevokedReason.REPLAY_SUPERSEDED, now);
                token.repointTo(next.tokenId());
                log.info("Refresh replay for user {} within {}: unused successor {} superseded", token.getUserId(),
                        session.replayWindow(), successor.getId());
                return next;
            }
        }

        log.warn("Reuse of a {} refresh token for user {}; revoking all of the user's tokens",
                token.getRevokedReason(), token.getUserId());
        repository.revokeAllForUser(token.getUserId(), RevokedReason.REUSE_DETECTED, now);
        throw new InvalidRefreshTokenException();
    }

    /** Logout: revokes the token if it is still valid; unknown or already revoked tokens are ignored. */
    @Transactional
    public void revoke(String raw) {
        if (raw != null && !raw.isBlank()) {
            repository.lockByTokenHash(hash(raw)).ifPresent(t -> t.revoke(RevokedReason.LOGOUT, clock.instant()));
        }
    }

    /** Revokes every valid token of the user (e.g. the user was disabled). */
    @Transactional
    public void revokeAllForUser(Long userId) {
        repository.revokeAllForUser(userId, RevokedReason.LOGOUT, clock.instant());
    }

    private String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
