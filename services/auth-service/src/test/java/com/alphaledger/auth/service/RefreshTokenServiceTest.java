package com.alphaledger.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.alphaledger.auth.config.SessionProperties;
import com.alphaledger.auth.domain.RefreshToken;
import com.alphaledger.auth.domain.RefreshToken.RevokedReason;
import com.alphaledger.auth.exception.InvalidRefreshTokenException;
import com.alphaledger.auth.repository.RefreshTokenRepository;
import com.alphaledger.auth.service.RefreshTokenService.Rotation;
import com.alphaledger.common.security.JwtProperties;

/**
 * Rotation, reuse detection and the narrow lost-response replay, against an in-memory repository and a clock the test
 * moves. Invariant checked throughout: at most one token of a user's chain is ever active.
 */
class RefreshTokenServiceTest {

    private static final Instant START = Instant.parse("2026-10-15T09:00:00Z");
    private static final String KEY = "attempt-0001-abcdef";

    private final MutableClock clock = new MutableClock(START);
    private final List<RefreshToken> rows = new ArrayList<>();
    private final RefreshTokenRepository repository = fakeRepository();
    private final JwtProperties jwt = new JwtProperties(null, Base64.getEncoder().encodeToString(new byte[32]), null,
            Duration.ofDays(7));
    private RefreshTokenService service = service(Duration.ofSeconds(30));

    @Test
    void issueStoresOnlyTheHashWithSevenDayExpiry() {
        Rotation issued = service.issue(2L);

        RefreshToken row = row(issued);
        assertThat(row.getTokenHash()).isEqualTo(RefreshTokenService.hash(issued.refreshToken()))
                .isNotEqualTo(issued.refreshToken());
        assertThat(row.getExpiresAt()).isEqualTo(START.plus(Duration.ofDays(7))).isEqualTo(issued.expiresAt());
        assertThat(issued.refreshToken()).hasSizeGreaterThanOrEqualTo(43);
    }

    @Test
    void rotationSpendsTheTokenAndLinksItsSuccessor() {
        Rotation first = service.issue(2L);
        Rotation second = service.rotate(first.refreshToken(), KEY);

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(row(first).getRevokedReason()).isEqualTo(RevokedReason.ROTATED);
        assertThat(row(first).getReplacedBy()).isEqualTo(second.tokenId());
        assertThat(row(first).getRotationKeyHash()).isEqualTo(RefreshTokenService.hash(KEY)).isNotEqualTo(KEY);
        assertThat(activeTokens()).containsExactly(second.tokenId());
    }

    @Test
    void reusingARotatedTokenRevokesEveryTokenOfTheUser() {
        Rotation first = service.issue(2L);
        Rotation second = service.rotate(first.refreshToken(), null);
        Rotation otherDevice = service.issue(2L);

        assertThatThrownBy(() -> service.rotate(first.refreshToken(), null))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(activeTokens()).isEmpty();
        assertThat(row(second).getRevokedReason()).isEqualTo(RevokedReason.REUSE_DETECTED);
        assertThat(row(otherDevice).getRevokedReason()).isEqualTo(RevokedReason.REUSE_DETECTED);
    }

    @Test
    void aLostResponseRetriedWithTheSameKeyGetsAFreshTokenAndTheUnusedSuccessorDies() {
        Rotation first = service.issue(2L);
        Rotation lost = service.rotate(first.refreshToken(), KEY);   // the response never reached the client

        clock.advance(Duration.ofSeconds(5));
        Rotation retried = service.rotate(first.refreshToken(), KEY);

        assertThat(retried.refreshToken()).isNotEqualTo(lost.refreshToken());
        assertThat(row(lost).getRevokedReason()).isEqualTo(RevokedReason.REPLAY_SUPERSEDED);
        assertThat(row(first).getReplacedBy()).isEqualTo(retried.tokenId());
        assertThat(activeTokens()).containsExactly(retried.tokenId());
        // The lost token can never be used: presenting it is reuse.
        assertThatThrownBy(() -> service.rotate(lost.refreshToken(), null))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(activeTokens()).isEmpty();
    }

    @Test
    void repeatedRetriesOfOneAttemptKeepExactlyOneActiveToken() {
        Rotation first = service.issue(2L);
        service.rotate(first.refreshToken(), KEY);
        service.rotate(first.refreshToken(), KEY);
        Rotation last = service.rotate(first.refreshToken(), KEY);

        assertThat(activeTokens()).containsExactly(last.tokenId());
    }

    @Test
    void aStolenTokenPresentedWithoutTheRequestsKeyIsReuse() {
        Rotation first = service.issue(2L);
        service.rotate(first.refreshToken(), KEY);

        assertThatThrownBy(() -> service.rotate(first.refreshToken(), "attacker-guess-0000"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(activeTokens()).isEmpty();
    }

    @Test
    void aReplayAfterTheWindowIsReuse() {
        Rotation first = service.issue(2L);
        service.rotate(first.refreshToken(), KEY);

        clock.advance(Duration.ofSeconds(30));   // the window is exclusive
        assertThatThrownBy(() -> service.rotate(first.refreshToken(), KEY))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(activeTokens()).isEmpty();
    }

    @Test
    void aReplayAfterTheSuccessorWasUsedIsReuse() {
        Rotation first = service.issue(2L);
        Rotation second = service.rotate(first.refreshToken(), KEY);
        service.rotate(second.refreshToken(), "attempt-0002-abcdef");   // the client did receive and use it

        assertThatThrownBy(() -> service.rotate(first.refreshToken(), KEY))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(activeTokens()).isEmpty();
    }

    @Test
    void aZeroWindowDisablesReplay() {
        service = service(Duration.ZERO);
        Rotation first = service.issue(2L);
        service.rotate(first.refreshToken(), KEY);

        assertThatThrownBy(() -> service.rotate(first.refreshToken(), KEY))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(activeTokens()).isEmpty();
    }

    @Test
    void aLoggedOutTokenCannotBeReplayedEvenWithAKey() {
        Rotation first = service.issue(2L);
        Rotation other = service.issue(2L);
        service.revoke(first.refreshToken());

        assertThat(row(first).getRevokedReason()).isEqualTo(RevokedReason.LOGOUT);
        assertThatThrownBy(() -> service.rotate(first.refreshToken(), KEY))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(row(other).getRevokedReason()).isEqualTo(RevokedReason.REUSE_DETECTED);
    }

    @Test
    void expiredUnknownAndBlankTokensAreRejectedWithoutRevokingAnything() {
        Rotation token = service.issue(2L);
        Rotation other = service.issue(2L);
        clock.advance(Duration.ofDays(7));

        assertThatThrownBy(() -> service.rotate(token.refreshToken(), null)).isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> service.rotate("unknown", null)).isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> service.rotate(" ", null)).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(row(token).isRevoked()).isFalse();
        assertThat(row(other).isRevoked()).isFalse();
    }

    @Test
    void theReplayWindowIsBounded() {
        assertThatThrownBy(() -> new SessionProperties(true, null, null, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionProperties(true, null, null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RefreshTokenService service(Duration window) {
        return new RefreshTokenService(repository, jwt, new SessionProperties(true, null, List.of(), window), clock);
    }

    private List<Long> activeTokens() {
        return rows.stream().filter(t -> t.isActive(clock.instant())).map(RefreshToken::getId).toList();
    }

    private RefreshToken row(Rotation rotation) {
        return rows.stream().filter(t -> t.getId().equals(rotation.tokenId())).findFirst().orElseThrow();
    }

    private RefreshTokenRepository fakeRepository() {
        RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
        AtomicLong ids = new AtomicLong();
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            RefreshToken token = inv.getArgument(0);
            ReflectionTestUtils.setField(token, "id", ids.incrementAndGet());
            rows.add(token);
            return token;
        });
        when(repo.lockByTokenHash(anyString())).thenAnswer(inv -> byHash(inv.getArgument(0)));
        when(repo.findByTokenHash(anyString())).thenAnswer(inv -> byHash(inv.getArgument(0)));
        when(repo.lockById(anyLong())).thenAnswer(inv -> rows.stream()
                .filter(t -> t.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.revokeAllForUser(anyLong(), any(), any())).thenAnswer(inv -> {
            int count = 0;
            for (RefreshToken t : rows) {
                if (t.getUserId().equals(inv.getArgument(0)) && !t.isRevoked()) {
                    t.revoke(inv.getArgument(1), inv.getArgument(2));
                    count++;
                }
            }
            return count;
        });
        return repo;
    }

    private Optional<RefreshToken> byHash(String hash) {
        return rows.stream().filter(t -> t.getTokenHash().equals(hash)).findFirst();
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
