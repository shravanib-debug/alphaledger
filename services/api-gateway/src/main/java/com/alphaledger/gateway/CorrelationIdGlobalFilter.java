package com.alphaledger.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import com.alphaledger.common.web.CorrelationId;

/**
 * Ensures every request routed by the gateway carries a correlation ID: keeps a valid incoming
 * {@value CorrelationId#HEADER}, otherwise creates one, forwards it downstream and returns it to the caller.
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = CorrelationId.sanitizeOrGenerate(
                exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(CorrelationId.HEADER, correlationId))
                .build();
        // Set just before commit so the downstream service's echo of the header is replaced, not duplicated.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);
            return Mono.empty();
        });

        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
