package com.alphaledger.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.alphaledger.auth.domain.RefreshToken;
import com.alphaledger.auth.exception.InvalidRefreshTokenException;
import com.alphaledger.auth.repository.RefreshTokenRepository;
import com.alphaledger.common.security.JwtProperties;

class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:00:00Z");

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final JwtProperties properties = new JwtProperties(null,
            Base64.getEncoder().encodeToString(new byte[32]), null, Duration.ofDays(7));
    private final RefreshTokenService service =
            new RefreshTokenService(repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void issueStoresOnlyTheHashWithSevenDayExpiry() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String raw = service.issue(2L);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTokenHash()).isEqualTo(RefreshTokenService.hash(raw)).isNotEqualTo(raw);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(raw).hasSizeGreaterThanOrEqualTo(43);
    }

    @Test
    void consumeRevokesTheTokenAndReturnsItsUser() {
        RefreshToken token = token(NOW.plusSeconds(60));
        when(repository.findByTokenHash(RefreshTokenService.hash("raw"))).thenReturn(Optional.of(token));

        assertThat(service.consume("raw")).isEqualTo(2L);
        assertThat(token.isRevoked()).isTrue();
    }

    @Test
    void reusingARevokedTokenRevokesAllTokensOfThatUser() {
        RefreshToken token = token(NOW.plusSeconds(60));
        token.revoke();
        when(repository.findByTokenHash(RefreshTokenService.hash("raw"))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.consume("raw")).isInstanceOf(InvalidRefreshTokenException.class);
        verify(repository).revokeAllForUser(2L);
    }

    @Test
    void expiredAndUnknownTokensAreRejected() {
        when(repository.findByTokenHash(RefreshTokenService.hash("expired"))).thenReturn(Optional.of(token(NOW)));
        when(repository.findByTokenHash(RefreshTokenService.hash("unknown"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consume("expired")).isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> service.consume("unknown")).isInstanceOf(InvalidRefreshTokenException.class);
        verify(repository, never()).revokeAllForUser(any());
    }

    private static RefreshToken token(Instant expiresAt) {
        return new RefreshToken(2L, "hash", NOW.minusSeconds(60), expiresAt);
    }
}
