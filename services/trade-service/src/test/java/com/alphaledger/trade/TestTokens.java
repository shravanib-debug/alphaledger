package com.alphaledger.trade;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import com.alphaledger.common.security.JwtProperties;

/** Mints real HS256 tokens, signed like auth-service's, so tests exercise the full validation path. */
final class TestTokens {

    static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC10ZXN0LW9ubHktc2VjcmV0LTEyMzQ1Ng==";
    private static final JwtProperties PROPERTIES = new JwtProperties(null, SECRET, null, null);
    private static final NimbusJwtEncoder ENCODER = new NimbusJwtEncoder(new ImmutableSecret<>(PROPERTIES.secretKey()));

    private TestTokens() {
    }

    static String bearer(long userId, String role) {
        return bearer(userId, role, PROPERTIES.issuer(), Instant.now().plus(Duration.ofMinutes(15)));
    }

    static String bearer(long userId, String role, String issuer, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .issuedAt(expiresAt.minus(Duration.ofMinutes(15)))
                .expiresAt(expiresAt)
                .claim("email", "user" + userId + "@alphaledger.test")
                .claim("roles", List.of(role))
                .build();
        return "Bearer " + ENCODER.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
