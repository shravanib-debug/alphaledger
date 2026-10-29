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
import com.alphaledger.auth.service.RefreshTokenService.Rotation;

/** Login, token refresh and logout. How the refresh token travels (body or cookie) is the controller's concern. */
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

    /** The response body plus the issued refresh token, which the controller puts in the body or a cookie. */
    public record Session(TokenResponse tokens, Rotation refresh) {
    }

    @Transactional
    public Session login(String email, String password) {
        UserAccount user = users.findByEmail(normalize(email)).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash()) || !user.isActive()) {
            throw new InvalidCredentialsException();
        }
        return session(user, refreshTokens.issue(user.getId()));
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Session refresh(String refreshToken, String idempotencyKey) {
        Rotation rotation = refreshTokens.rotate(refreshToken, idempotencyKey);
        UserAccount user = users.findById(rotation.userId()).orElse(null);
        if (user == null || !user.isActive()) {
            // A disabled user keeps no valid refresh token, including the one just issued.
            refreshTokens.revokeAllForUser(rotation.userId());
            throw new InvalidRefreshTokenException();
        }
        return session(user, rotation);
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    private Session session(UserAccount user, Rotation refresh) {
        return new Session(TokenResponse.bearer(accessTokens.issue(user), accessTokens.ttlSeconds(),
                refresh.refreshToken(), UserResponse.from(user)), refresh);
    }

    static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
