package com.alphaledger.gateway;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Runs the real gateway with no downstream services registered. A request that passes authentication is routed
 * and then fails with 503 (no instance); a request that fails authentication never gets that far (401).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.config.enabled=false",
        "alphaledger.security.jwt.secret=" + TestTokens.SECRET
})
class GatewaySecurityTest {

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void protectedRoutesRejectMissingAndInvalidTokensWithStandardError() {
        client.get().uri("/api/v1/portfolios").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody()
                .jsonPath("$.error").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.path").isEqualTo("/api/v1/portfolios");

        client.get().uri("/api/v1/trades").header("Authorization", "Bearer not.a.jwt").exchange()
                .expectStatus().isUnauthorized();
        client.get().uri("/api/v1/trades")
                .header("Authorization", TestTokens.bearer(2, "PM", "alphaledger-auth", Instant.now().minusSeconds(300)))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void validTokenPassesTheGateway() {
        client.get().uri("/api/v1/portfolios").header("Authorization", TestTokens.bearer(4, "AUDITOR")).exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void loginAndHealthArePublic() {
        client.post().uri("/api/v1/auth/login").exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk();
    }

    @Test
    void cookieModeAuthPreflightFromTheDevServerIsAllowedWithCredentialsAndOtherOriginsAreNot() {
        client.options().uri("/api/v1/auth/refresh")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-client,idempotency-key,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
        client.options().uri("/api/v1/auth/refresh")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-client")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void corsPreflightFromTheFrontendIsAllowedWithoutAToken() {
        client.options().uri("/api/v1/portfolios")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
    }
}
