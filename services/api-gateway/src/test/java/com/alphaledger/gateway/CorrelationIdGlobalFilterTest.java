package com.alphaledger.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import com.alphaledger.common.web.CorrelationId;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void forwardsValidIncomingIdDownstreamAndToCaller() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/trades").header(CorrelationId.HEADER, "abc-123"));

        String forwarded = run(exchange, null);

        assertThat(forwarded).isEqualTo("abc-123");
        assertThat(exchange.getResponse().getHeaders().get(CorrelationId.HEADER)).containsExactly("abc-123");
    }

    @Test
    void replacesDownstreamEchoInsteadOfDuplicatingIt() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/trades").header(CorrelationId.HEADER, "abc-123"));

        run(exchange, "abc-123");

        assertThat(exchange.getResponse().getHeaders().get(CorrelationId.HEADER)).containsExactly("abc-123");
    }

    @Test
    void createsIdWhenMissingOrUnsafe() {
        MockServerWebExchange missing = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/trades"));
        MockServerWebExchange unsafe = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/trades").header(CorrelationId.HEADER, "x".repeat(200)));

        assertThat(run(missing, null)).isNotBlank()
                .isEqualTo(missing.getResponse().getHeaders().getFirst(CorrelationId.HEADER));
        assertThat(run(unsafe, null)).isNotBlank().hasSizeLessThanOrEqualTo(64);
    }

    /** Runs the filter with a fake downstream that optionally echoes the header, then commits the response. */
    private String run(MockServerWebExchange exchange, String downstreamEcho) {
        AtomicReference<String> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = (ServerWebExchange downstream) -> {
            forwarded.set(downstream.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
            if (downstreamEcho != null) {
                downstream.getResponse().getHeaders().add(CorrelationId.HEADER, downstreamEcho);
            }
            return Mono.empty();
        };
        filter.filter(exchange, chain).then(exchange.getResponse().setComplete()).block();
        return forwarded.get();
    }
}
