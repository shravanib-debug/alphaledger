package com.alphaledger.auth.controller;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alphaledger.auth.config.SessionProperties;
import com.alphaledger.auth.dto.LoginRequest;
import com.alphaledger.auth.dto.RefreshRequest;
import com.alphaledger.auth.dto.TokenResponse;
import com.alphaledger.auth.dto.UserResponse;
import com.alphaledger.auth.exception.InvalidRefreshTokenException;
import com.alphaledger.auth.service.AuthService;
import com.alphaledger.auth.service.AuthService.Session;
import com.alphaledger.auth.service.UserService;
import com.alphaledger.common.error.ValidationException;
import com.alphaledger.common.security.AuthenticatedUser;

/**
 * Login, refresh and logout in two modes (docs/auth-session.md):
 * <ul>
 * <li><b>API clients</b> (Postman, curl, scripts, service tests): the refresh token travels in the JSON body, as
 * before.</li>
 * <li><b>Browsers</b>, which send {@code X-Client: web}: the refresh token travels only in an HttpOnly,
 * SameSite=Strict cookie scoped to {@code /api/v1/auth}, and is never in a response body. Each request must pass the
 * same-origin checks in {@link BrowserSession}.</li>
 * </ul>
 * Refresh accepts an optional {@code Idempotency-Key}: a retry of the same attempt after a lost response, within the
 * replay window, gets a fresh pair instead of being treated as token reuse.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    private final AuthService authService;
    private final UserService userService;
    private final BrowserSession browser;

    public AuthController(AuthService authService, UserService userService, BrowserSession browser) {
        this.authService = authService;
        this.userService = userService;
        this.browser = browser;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http,
                               HttpServletResponse response) {
        boolean web = browser.isWebClient(http);
        if (web) {
            browser.verifySameOrigin(http);
        }
        return respond(authService.login(request.email(), request.password()), web, response);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@RequestBody(required = false) RefreshRequest request,
                                 @RequestHeader(name = SessionProperties.IDEMPOTENCY_KEY_HEADER, required = false)
                                 String idempotencyKey,
                                 HttpServletRequest http, HttpServletResponse response) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new ValidationException("Idempotency-Key: must be 16-128 characters of A-Z, a-z, 0-9, '-' or '_'");
        }
        boolean web = browser.isWebClient(http);
        if (!web) {
            return respond(authService.refresh(bodyToken(request), idempotencyKey), false, response);
        }
        browser.verifySameOrigin(http);
        try {
            String token = browser.readRefreshToken(http).orElseThrow(InvalidRefreshTokenException::new);
            return respond(authService.refresh(token, idempotencyKey), true, response);
        } catch (InvalidRefreshTokenException ex) {
            browser.clearRefreshToken(response);   // a dead cookie is removed so the browser stops sending it
            throw ex;
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest request, HttpServletRequest http,
                                       HttpServletResponse response) {
        if (!browser.isWebClient(http)) {
            authService.logout(bodyToken(request));
            return ResponseEntity.noContent().build();
        }
        browser.verifySameOrigin(http);
        browser.readRefreshToken(http).ifPresent(authService::logout);
        browser.clearRefreshToken(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        return userService.get(AuthenticatedUser.from(authentication).id());
    }

    private TokenResponse respond(Session session, boolean web, HttpServletResponse response) {
        if (!web) {
            return session.tokens();
        }
        browser.writeRefreshToken(response, session.refresh().refreshToken(), session.refresh().expiresAt());
        return session.tokens().withoutRefreshToken();
    }

    private static String bodyToken(RefreshRequest request) {
        if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()) {
            throw new ValidationException("refreshToken: must not be blank");
        }
        return request.refreshToken();
    }
}
