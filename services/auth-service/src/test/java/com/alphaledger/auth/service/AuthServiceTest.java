package com.alphaledger.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.alphaledger.auth.domain.Role;
import com.alphaledger.auth.domain.UserAccount;
import com.alphaledger.auth.domain.UserStatus;
import com.alphaledger.auth.dto.TokenResponse;
import com.alphaledger.auth.exception.InvalidCredentialsException;
import com.alphaledger.auth.exception.InvalidRefreshTokenException;
import com.alphaledger.auth.repository.UserAccountRepository;

class AuthServiceTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AccessTokenService accessTokens = mock(AccessTokenService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final AuthService service = new AuthService(users, encoder, accessTokens, refreshTokens);

    @Test
    void loginWithCorrectPasswordIssuesBothTokens() {
        UserAccount priya = AccessTokenServiceTest.user(2L, Role.PM);
        ReflectionTestUtils.setField(priya, "passwordHash", encoder.encode("correct horse battery"));
        when(users.findByEmail("priya.pm@alphaledger.test")).thenReturn(Optional.of(priya));
        when(accessTokens.issue(priya)).thenReturn("access");
        when(accessTokens.ttlSeconds()).thenReturn(900L);
        when(refreshTokens.issue(2L)).thenReturn(rotation("refresh"));

        TokenResponse response = service.login("  Priya.PM@AlphaLedger.test ", "correct horse battery").tokens();

        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(response.user().role()).isEqualTo(Role.PM);
    }

    @Test
    void wrongPasswordUnknownEmailAndDisabledUserAllFailTheSameWay() {
        UserAccount priya = AccessTokenServiceTest.user(2L, Role.PM);
        ReflectionTestUtils.setField(priya, "passwordHash", encoder.encode("correct horse battery"));
        UserAccount disabled = AccessTokenServiceTest.user(5L, Role.PM);
        ReflectionTestUtils.setField(disabled, "passwordHash", encoder.encode("correct horse battery"));
        ReflectionTestUtils.setField(disabled, "status", UserStatus.DISABLED);
        when(users.findByEmail("priya.pm@alphaledger.test")).thenReturn(Optional.of(priya));
        when(users.findByEmail("disabled@alphaledger.test")).thenReturn(Optional.of(disabled));
        when(users.findByEmail("nobody@alphaledger.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("priya.pm@alphaledger.test", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class).hasMessage("Invalid email or password");
        assertThatThrownBy(() -> service.login("nobody@alphaledger.test", "whatever"))
                .isInstanceOf(InvalidCredentialsException.class).hasMessage("Invalid email or password");
        assertThatThrownBy(() -> service.login("disabled@alphaledger.test", "correct horse battery"))
                .isInstanceOf(InvalidCredentialsException.class).hasMessage("Invalid email or password");
        verify(refreshTokens, never()).issue(org.mockito.ArgumentMatchers.anyLong());
        verify(accessTokens, never()).issue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refreshIssuesNewTokensForActiveUser() {
        UserAccount priya = AccessTokenServiceTest.user(2L, Role.PM);
        when(refreshTokens.rotate("old", "key")).thenReturn(rotation("new-refresh"));
        when(users.findById(2L)).thenReturn(Optional.of(priya));
        when(accessTokens.issue(priya)).thenReturn("new-access");

        TokenResponse response = service.refresh("old", "key").tokens();

        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
    }

    @Test
    void refreshForADisabledUserRevokesEveryTokenIncludingTheNewOne() {
        UserAccount disabled = AccessTokenServiceTest.user(2L, Role.PM);
        ReflectionTestUtils.setField(disabled, "status", UserStatus.DISABLED);
        when(refreshTokens.rotate("old", null)).thenReturn(rotation("new-refresh"));
        when(users.findById(2L)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.refresh("old", null)).isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokens).revokeAllForUser(2L);
        verify(accessTokens, never()).issue(org.mockito.ArgumentMatchers.any());
    }

    private static RefreshTokenService.Rotation rotation(String raw) {
        return new RefreshTokenService.Rotation(10L, 2L, raw, java.time.Instant.parse("2026-10-22T09:00:00Z"));
    }
}
