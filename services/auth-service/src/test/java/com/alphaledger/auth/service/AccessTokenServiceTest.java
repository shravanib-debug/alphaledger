package com.alphaledger.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import com.alphaledger.auth.domain.Role;
import com.alphaledger.auth.domain.UserAccount;
import com.alphaledger.common.security.JwtProperties;
import com.alphaledger.common.security.JwtSupport;

class AccessTokenServiceTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    private final JwtProperties properties = new JwtProperties("alphaledger-auth", SECRET, Duration.ofMinutes(15), null);

    @Test
    void issuesTokenWithIdentityRolesAndFifteenMinuteExpiry() {
        Instant now = Instant.now();
        AccessTokenService service = service(Clock.fixed(now, ZoneOffset.UTC));

        Jwt jwt = JwtSupport.decoder(properties).decode(service.issue(user(2L, Role.PM)));

        assertThat(jwt.getSubject()).isEqualTo("2");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("priya.pm@alphaledger.test");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("PM");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("alphaledger-auth");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(service.ttlSeconds()).isEqualTo(900);
    }

    @Test
    void expiredTokenIsRejectedByTheSharedDecoder() {
        AccessTokenService service = service(Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));
        String token = service.issue(user(2L, Role.PM));

        assertThatThrownBy(() -> JwtSupport.decoder(properties).decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtProperties other = new JwtProperties(null, Base64.getEncoder().encodeToString("x".repeat(32).getBytes()),
                null, null);
        String token = new AccessTokenService(new NimbusJwtEncoder(new ImmutableSecret<>(other.secretKey())), other,
                Clock.systemUTC()).issue(user(1L, Role.ADMIN));

        assertThatThrownBy(() -> JwtSupport.decoder(properties).decode(token))
                .hasMessageContaining("signature");
    }

    private AccessTokenService service(Clock clock) {
        return new AccessTokenService(new NimbusJwtEncoder(new ImmutableSecret<>(properties.secretKey())),
                properties, clock);
    }

    static UserAccount user(Long id, Role role) {
        UserAccount user = new UserAccount("priya.pm@alphaledger.test", "hash", "Priya", role, Instant.now());
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
