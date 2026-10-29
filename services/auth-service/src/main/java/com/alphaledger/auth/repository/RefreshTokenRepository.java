package com.alphaledger.auth.repository;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.alphaledger.auth.domain.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Loads the token with a row lock ({@code SELECT ... FOR UPDATE}), so concurrent refreshes of the same token run
     * one after the other: the second sees the first's rotation instead of rotating the token a second time.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> lockByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.id = :id")
    Optional<RefreshToken> lockById(@Param("id") Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revoked = true, t.revokedReason = :reason, t.revokedAt = :now
            where t.userId = :userId and t.revoked = false
            """)
    int revokeAllForUser(@Param("userId") Long userId, @Param("reason") RefreshToken.RevokedReason reason,
                         @Param("now") Instant now);
}
