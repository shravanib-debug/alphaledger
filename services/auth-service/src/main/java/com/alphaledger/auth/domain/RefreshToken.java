package com.alphaledger.auth.domain;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A refresh token, stored only as the SHA-256 hash of the value given to the client. A token is used at most once:
 * rotation revokes it ({@code ROTATED}) and links it to its successor ({@code replaced_by}).
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    public enum RevokedReason { ROTATED, LOGOUT, REUSE_DETECTED, REPLAY_SUPERSEDED, LEGACY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason")
    private RevokedReason revokedReason;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private Long replacedBy;

    @Column(name = "rotation_key_hash")
    private String rotationKeyHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefreshToken() {
    }

    public RefreshToken(Long userId, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isActive(Instant now) {
        return !revoked && !isExpired(now);
    }

    /** Spent by a rotation: replaced by {@code successorId}; {@code keyHash} identifies the rotating request. */
    public void rotate(Long successorId, String keyHash, Instant now) {
        revoke(RevokedReason.ROTATED, now);
        this.replacedBy = successorId;
        this.rotationKeyHash = keyHash;
    }

    /** A replay of this token's rotation issued a newer successor. */
    public void repointTo(Long successorId) {
        this.replacedBy = successorId;
    }

    public void revoke(RevokedReason reason, Instant now) {
        if (!revoked) {
            this.revoked = true;
            this.revokedReason = reason;
            this.revokedAt = now;
        }
    }

    /**
     * True when this token was rotated by a request carrying {@code keyHash}, less than {@code window} ago: the only
     * case in which presenting it again is treated as that request's retry rather than as reuse.
     */
    public boolean isReplayOf(String keyHash, Instant now, Duration window) {
        return revokedReason == RevokedReason.ROTATED && keyHash != null && keyHash.equals(rotationKeyHash)
                && replacedBy != null && revokedAt != null && now.isBefore(revokedAt.plus(window));
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public RevokedReason getRevokedReason() {
        return revokedReason;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Long getReplacedBy() {
        return replacedBy;
    }

    public String getRotationKeyHash() {
        return rotationKeyHash;
    }
}
