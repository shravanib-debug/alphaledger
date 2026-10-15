package com.alphaledger.auth.service;

import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.auth.domain.UserAccount;
import com.alphaledger.auth.dto.TokenResponse;
import com.alphaledger.auth.dto.UserResponse;
import com.alphaledger.auth.exception.InvalidCredentialsException;
import com.alphaledger.auth.exception.InvalidRefreshTokenException;
import com.alphaledger.auth.repository.UserAccountRepository;

/** Login, token refresh and logout. */
@Service
public class AuthService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    // Checked against when the email is unknown, so both paths take a similar time.
    private final String dummyHash;

    public AuthService(UserAccountRepository users, PasswordEncoder passwordEncoder,
                       AccessTokenService accessTokens, RefreshTokenService refreshTokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public TokenResponse login(String email, String password) {
        UserAccount user = users.findByEmail(normalize(email)).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash()) || !user.isActive()) {
            throw new InvalidCredentialsException();
        }
        return issueTokens(user);
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenResponse refresh(String refreshToken) {
        Long userId = refreshTokens.consume(refreshToken);
        UserAccount user = users.findById(userId)
                .filter(UserAccount::isActive)
                .orElseThrow(InvalidRefreshTokenException::new);
        return issueTokens(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    private TokenResponse issueTokens(UserAccount user) {
        return TokenResponse.bearer(accessTokens.issue(user), accessTokens.ttlSeconds(),
                refreshTokens.issue(user.getId()), UserResponse.from(user));
    }

    static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
