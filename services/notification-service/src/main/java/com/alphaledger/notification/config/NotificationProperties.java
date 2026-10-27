package com.alphaledger.notification.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code alphaledger.notification.*}: email settings and who is emailed for each audience role.
 *
 * @param recipients email addresses per role (e.g. {@code OPS_ANALYST}); a role without any is not emailed
 */
@ConfigurationProperties(prefix = "alphaledger.notification")
public record NotificationProperties(Email email, Map<String, List<String>> recipients) {

    public NotificationProperties {
        email = email == null ? new Email(null, null, null) : email;
        recipients = recipients == null ? Map.of() : recipients;
    }

    public List<String> recipientsFor(String role) {
        return recipients.getOrDefault(role, List.of());
    }

    /** {@code enabled = false} marks new notifications SKIPPED instead of emailing them. */
    public record Email(Boolean enabled, String from, Integer maxAttempts) {

        public Email {
            enabled = enabled == null || enabled;
            from = from == null ? "alerts@alphaledger.test" : from;
            maxAttempts = maxAttempts == null ? 5 : maxAttempts;
        }
    }
}
