package com.alphaledger.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.common.error.ErrorResponse;
import com.alphaledger.common.security.JwtProperties;
import com.alphaledger.common.security.JwtSupport;
import com.alphaledger.common.web.CorrelationId;

/**
 * Edge authentication (architecture.md §8): every route except login/refresh/logout, health and the smoke-test
 * ping needs a valid access token. Role checks happen in the services ({@code @PreAuthorize}).
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/api/v1/ping/**"
    };

    private final JsonMapper jsonMapper = new JsonMapper();

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(JwtProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(properties.secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtSupport.validator(properties));
        return decoder;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        ServerAuthenticationEntryPoint unauthorized = (exchange, ex) -> write(exchange, HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED", "Missing, invalid or expired access token");
        ServerAccessDeniedHandler forbidden = (exchange, ex) -> write(exchange, HttpStatus.FORBIDDEN,
                "FORBIDDEN", "Access denied");
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()   // CORS preflight
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                new ReactiveJwtAuthenticationConverterAdapter(JwtSupport.authenticationConverter())))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .build();
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String error, String message) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER);
        ErrorResponse body = new ErrorResponse(Instant.now(), status.value(), error, message,
                exchange.getRequest().getPath().value(), correlationId);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.UNAUTHORIZED) {
            exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
        }
        byte[] bytes = jsonMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
