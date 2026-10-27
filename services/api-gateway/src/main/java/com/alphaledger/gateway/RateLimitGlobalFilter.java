package com.alphaledger.gateway;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.common.error.ErrorResponse;
import com.alphaledger.common.web.CorrelationId;

/**
 * Per-caller rate limit (token bucket): each caller may burst {@code capacity} requests, refilled at
 * {@code refill-per-second}. The caller is the verified user ID ({@value UserHeadersGlobalFilter#USER_ID}, set by the
 * filter before this one), or the client IP before login. Over the limit: 429 with {@code Retry-After}. Kept in
 * memory, so the limit is per gateway instance; a shared store (Redis) would be needed with several instances.
 */
@Component
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    private final boolean enabled;
    private final double capacity;
    private final double refillPerSecond;
    private final JsonMapper jsonMapper;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitGlobalFilter(@Value("${alphaledger.gateway.rate-limit.enabled:true}") boolean enabled,
                                 @Value("${alphaledger.gateway.rate-limit.capacity:100}") double capacity,
                                 @Value("${alphaledger.gateway.rate-limit.refill-per-second:20}") double refillPerSecond,
                                 JsonMapper jsonMapper) {
        this.enabled = enabled;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled || buckets.computeIfAbsent(key(exchange), k -> new Bucket(capacity)).tryTake(capacity,
                refillPerSecond, System.nanoTime())) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", "1");
        ErrorResponse body = new ErrorResponse(Instant.now(), HttpStatus.TOO_MANY_REQUESTS.value(), "RATE_LIMITED",
                "Too many requests; slow down and retry shortly", exchange.getRequest().getPath().value(),
                exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
        DataBuffer buffer = response.bufferFactory().wrap(jsonMapper.writeValueAsString(body)
                .getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private static String key(ServerWebExchange exchange) {
        String user = exchange.getRequest().getHeaders().getFirst(UserHeadersGlobalFilter.USER_ID);
        if (user != null) {
            return "user:" + user;
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return "ip:" + (remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress());
    }

    /** Drops buckets that have refilled completely, so idle callers do not accumulate. */
    @Scheduled(fixedDelay = 60_000)
    public void evictIdle() {
        long now = System.nanoTime();
        buckets.entrySet().removeIf(e -> e.getValue().isFull(capacity, refillPerSecond, now));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;   // after the user headers are set
    }

    static final class Bucket {

        private double tokens;
        private long last;

        Bucket(double capacity) {
            this.tokens = capacity;
            this.last = System.nanoTime();
        }

        synchronized boolean tryTake(double capacity, double refillPerSecond, long now) {
            refill(capacity, refillPerSecond, now);
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        }

        synchronized boolean isFull(double capacity, double refillPerSecond, long now) {
            refill(capacity, refillPerSecond, now);
            return tokens >= capacity;
        }

        private void refill(double capacity, double refillPerSecond, long now) {
            tokens = Math.min(capacity, tokens + (now - last) / 1e9 * refillPerSecond);
            last = now;
        }
    }
}
