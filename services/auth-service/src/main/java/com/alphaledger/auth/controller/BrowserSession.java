package com.alphaledger.auth.controller;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.alphaledger.auth.config.SessionProperties;
import com.alphaledger.auth.exception.CsrfRejectedException;

/**
 * Cookie mode for browsers (docs/auth-session.md). A request opts in with {@code X-Client: web}; only then is the
 * refresh cookie read or written. Every cookie-mode request must pass, in order:
 * <ol>
 * <li>{@code Sec-Fetch-Site}, when the browser sends it, is not {@code cross-site};</li>
 * <li>{@code Origin} is present and exactly one of the configured origins.</li>
 * </ol>
 * Together with {@code SameSite=Strict}, the custom header (a cross-site page cannot send it without a CORS
 * preflight the gateway refuses) and JSON-only bodies, a forged cross-site request can neither use nor set the
 * cookie. Requests without the header are API clients and use the body, exactly as before.
 */
@Component
public class BrowserSession {

    private final SessionProperties properties;
    private final Clock clock;

    public BrowserSession(SessionProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isWebClient(HttpServletRequest request) {
        return SessionProperties.WEB_CLIENT_VALUE.equals(request.getHeader(SessionProperties.WEB_CLIENT_HEADER));
    }

    /**
     * Rejects a cookie-mode request that may be cross-site. Every value of a repeated header is checked: any
     * {@code cross-site} rejects, and exactly one Origin is required.
     */
    public void verifySameOrigin(HttpServletRequest request) {
        List<String> fetchSite = Collections.list(request.getHeaders("Sec-Fetch-Site"));
        if (fetchSite.stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("cross-site"))) {
            throw new CsrfRejectedException("cross-site request");
        }
        List<String> origins = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        if (origins.isEmpty() || origins.get(0).isBlank()) {
            throw new CsrfRejectedException("missing Origin header");
        }
        if (origins.size() > 1 || !properties.allowedOrigins().contains(origins.get(0))) {
            throw new CsrfRejectedException("origin not allowed");
        }
    }

    public Optional<String> readRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        return cookies == null ? Optional.empty() : Arrays.stream(cookies)
                .filter(c -> properties.cookieName().equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> !v.isBlank())
                .findFirst();
    }

    /** Sets the refresh cookie to expire with the token itself. */
    public void writeRefreshToken(HttpServletResponse response, String token, Instant expiresAt) {
        Duration maxAge = Duration.between(clock.instant(), expiresAt);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, maxAge.isNegative() ? Duration.ZERO : maxAge));
    }

    public void clearRefreshToken(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO));
    }

    private String cookie(String value, Duration maxAge) {
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path(properties.cookiePath())
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
