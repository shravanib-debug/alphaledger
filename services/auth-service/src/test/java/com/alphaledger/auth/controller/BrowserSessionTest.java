package com.alphaledger.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.alphaledger.auth.config.SessionProperties;
import com.alphaledger.auth.exception.CsrfRejectedException;

/** Cookie attributes in both Secure modes, and the same-origin checks on their own. */
class BrowserSessionTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void deployedHttpsUsesTheSecurePrefixAndFlag() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        session(true).writeRefreshToken(response, "abc", NOW.plus(Duration.ofDays(7)));

        // Expires is derived by ResponseCookie from the system clock, so it is checked by shape only.
        assertThat(response.getHeader("Set-Cookie"))
                .matches("__Secure-al_refresh=abc; Path=/api/v1/auth; Max-Age=604800; Expires=[^;]+ GMT; "
                        + "Secure; HttpOnly; SameSite=Strict");
    }

    @Test
    void localPlainHttpDropsOnlyTheSecureFlagAndPrefix() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        session(false).writeRefreshToken(response, "abc", NOW.plus(Duration.ofDays(7)));

        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie).startsWith("al_refresh=abc;").contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth")
                .doesNotContain("Secure");
    }

    @Test
    void clearingExpiresTheCookieImmediately() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        session(true).clearRefreshToken(response);

        assertThat(response.getHeader("Set-Cookie")).startsWith("__Secure-al_refresh=;").contains("Max-Age=0");
    }

    @Test
    void sameOriginChecks() {
        BrowserSession session = session(true);
        session.verifySameOrigin(request("http://localhost:3000", "same-origin"));
        session.verifySameOrigin(request("http://localhost:3000", null));   // older browsers omit Sec-Fetch-Site

        assertThatThrownBy(() -> session.verifySameOrigin(request(null, "same-origin")))
                .isInstanceOf(CsrfRejectedException.class).hasMessageContaining("missing Origin");
        assertThatThrownBy(() -> session.verifySameOrigin(request("http://localhost:3000.evil.example", null)))
                .isInstanceOf(CsrfRejectedException.class);
        assertThatThrownBy(() -> session.verifySameOrigin(request("http://LOCALHOST:3000", null)))
                .isInstanceOf(CsrfRejectedException.class);   // exact match only
        assertThatThrownBy(() -> session.verifySameOrigin(request("http://localhost:3000", "cross-site")))
                .isInstanceOf(CsrfRejectedException.class).hasMessageContaining("cross-site");
    }

    private static MockHttpServletRequest request(String origin, String fetchSite) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        if (fetchSite != null) {
            request.addHeader("Sec-Fetch-Site", fetchSite);
        }
        return request;
    }

    private static BrowserSession session(boolean secure) {
        return new BrowserSession(new SessionProperties(secure, null, List.of("http://localhost:3000"),
                Duration.ofSeconds(30)), CLOCK);
    }
}
