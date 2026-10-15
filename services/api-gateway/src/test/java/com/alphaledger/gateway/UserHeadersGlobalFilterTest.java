package com.alphaledger.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

class UserHeadersGlobalFilterTest {

    private final UserHeadersGlobalFilter filter = new UserHeadersGlobalFilter();

    @Test
    void replacesSpoofedHeadersWithTheVerifiedCaller() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject("2")
                .claim("email", "priya.pm@alphaledger.test").claim("roles", List.of("PM"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        MockServerWebExchange exchange = MockServerWebExchange.builder(MockServerHttpRequest.get("/api/v1/trades")
                        .header("X-User-Id", "1").header("X-User-Roles", "ADMIN"))
                .principal(new JwtAuthenticationToken(jwt))
                .build();

        HttpHeaders forwarded = run(exchange);

        assertThat(forwarded.getFirst("X-User-Id")).isEqualTo("2");
        assertThat(forwarded.getFirst("X-User-Email")).isEqualTo("priya.pm@alphaledger.test");
        assertThat(forwarded.get("X-User-Roles")).containsExactly("PM");
    }

    @Test
    void stripsUserHeadersFromAnonymousRequests() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                .header("X-User-Id", "1").header("X-User-Roles", "ADMIN"));

        HttpHeaders forwarded = run(exchange);

        assertThat(forwarded.containsHeader("X-User-Id")).isFalse();
        assertThat(forwarded.containsHeader("X-User-Roles")).isFalse();
    }

    private HttpHeaders run(MockServerWebExchange exchange) {
        AtomicReference<HttpHeaders> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = (ServerWebExchange downstream) -> {
            forwarded.set(downstream.getRequest().getHeaders());
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return forwarded.get();
    }
}
