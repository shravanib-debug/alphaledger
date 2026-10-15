package com.alphaledger.gateway;

import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import com.alphaledger.common.security.JwtClaims;

/**
 * Forwards the verified caller to downstream services as {@code X-User-Id}, {@code X-User-Email} and
 * {@code X-User-Roles} (for logging and auditing). Any such headers sent by the client are removed first,
 * so they cannot be spoofed. Services still authorize from the JWT itself (defense in depth).
 */
@Component
public class UserHeadersGlobalFilter implements GlobalFilter, Ordered {

    static final String USER_ID = "X-User-Id";
    static final String USER_EMAIL = "X-User-Email";
    static final String USER_ROLES = "X-User-Roles";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(auth -> withUserHeaders(exchange, auth))
                .defaultIfEmpty(withUserHeaders(exchange, null))
                .flatMap(chain::filter);
    }

    private static ServerWebExchange withUserHeaders(ServerWebExchange exchange, JwtAuthenticationToken auth) {
        ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove(USER_ID);
            headers.remove(USER_EMAIL);
            headers.remove(USER_ROLES);
            if (auth != null) {
                headers.set(USER_ID, auth.getToken().getSubject());
                String email = auth.getToken().getClaimAsString(JwtClaims.EMAIL);
                if (email != null) {
                    headers.set(USER_EMAIL, email);
                }
                List<String> roles = auth.getToken().getClaimAsStringList(JwtClaims.ROLES);
                if (roles != null) {
                    headers.set(USER_ROLES, String.join(",", roles));
                }
            }
        }).build();
        return exchange.mutate().request(request).build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
