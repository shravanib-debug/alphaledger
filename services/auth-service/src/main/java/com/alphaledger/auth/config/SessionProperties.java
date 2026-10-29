package com.alphaledger.auth.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Browser session settings ({@code alphaledger.auth.session.*}; docs/auth-session.md).
 *
 * @param cookieSecure   the {@code Secure} flag; true by default, false only for plain-HTTP local development
 * @param cookiePath     the refresh cookie is sent only to these endpoints
 * @param allowedOrigins browser origins allowed to use cookie mode (exact match, scheme://host[:port])
 * @param replayWindow   how long after a rotation the same request (same Idempotency-Key) may be replayed after a
 *                       lost response; zero disables replay
 */
@ConfigurationProperties(prefix = "alphaledger.auth.session")
public record SessionProperties(Boolean cookieSecure, String cookiePath, List<String> allowedOrigins,
                                Duration replayWindow) {

    public static final String WEB_CLIENT_HEADER = "X-Client";
    public static final String WEB_CLIENT_VALUE = "web";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    public SessionProperties {
        cookieSecure = cookieSecure == null || cookieSecure;
        cookiePath = cookiePath == null ? "/api/v1/auth" : cookiePath;
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        replayWindow = replayWindow == null ? Duration.ofSeconds(30) : replayWindow;
        if (replayWindow.isNegative() || replayWindow.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("alphaledger.auth.session.replay-window must be between 0 and 2 minutes");
        }
    }

    /** {@code __Secure-} prefix when Secure, so a browser rejects the cookie unless it came over HTTPS. */
    public String cookieName() {
        return cookieSecure ? "__Secure-al_refresh" : "al_refresh";
    }
}
