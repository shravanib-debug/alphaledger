package com.alphaledger.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The real gateway with no services registered: every routed call fails, so the route's circuit breaker answers
 * with the 503 fallback. The rate limit is set low (3 requests, almost no refill) to see a 429.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.config.enabled=false",
        "alphaledger.security.jwt.secret=" + TestTokens.SECRET,
        "alphaledger.gateway.rate-limit.capacity=3",
        "alphaledger.gateway.rate-limit.refill-per-second=0.01"
})
class GatewayResilienceTest {

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void anUnavailableServiceGetsTheStandard503FallbackAndTheCallerIsRateLimited() {
        String pm = TestTokens.bearer(2, "PM");
        // Three calls fit in the burst; each reaches the trade-service route, whose breaker falls back.
        for (int i = 0; i < 3; i++) {
            client.get().uri("/api/v1/trades").header("Authorization", pm).header("X-Correlation-Id", "cb-test")
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectBody()
                    .jsonPath("$.error").isEqualTo("SERVICE_UNAVAILABLE")
                    .jsonPath("$.path").isEqualTo("/api/v1/trades")
                    .jsonPath("$.correlationId").isEqualTo("cb-test");
        }
        client.get().uri("/api/v1/trades").header("Authorization", pm).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectBody().jsonPath("$.error").isEqualTo("RATE_LIMITED");

        // The limit is per caller: another user still gets through.
        client.get().uri("/api/v1/trades").header("Authorization", TestTokens.bearer(3, "OPS_ANALYST")).exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
