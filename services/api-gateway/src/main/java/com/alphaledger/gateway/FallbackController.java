package com.alphaledger.gateway;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.alphaledger.common.error.ErrorResponse;
import com.alphaledger.common.web.CorrelationId;

/**
 * Where a route's circuit breaker forwards when the service is down, slow or its breaker is open: a 503 in the
 * standard error format (architecture.md §7.2), returned at once instead of waiting for a timeout.
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/unavailable")
    public ResponseEntity<ErrorResponse> unavailable(ServerWebExchange exchange) {
        Set<URI> original = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        String path = original == null || original.isEmpty() ? exchange.getRequest().getPath().value()
                : original.iterator().next().getPath();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(Instant.now(),
                HttpStatus.SERVICE_UNAVAILABLE.value(), "SERVICE_UNAVAILABLE",
                "The service is temporarily unavailable; try again shortly", path,
                exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER)));
    }
}
