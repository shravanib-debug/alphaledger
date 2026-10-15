package com.alphaledger.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.config.enabled=false"
})
class GatewayRoutesTest {

    @Autowired
    private RouteLocator routeLocator;

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api/v1/auth/login,                          auth-service",
            "/api/v1/portfolios,                          portfolio-service",
            "/api/v1/portfolios/7,                        portfolio-service",
            "/api/v1/instruments,                         portfolio-service",
            "/api/v1/portfolios/7/positions,              position-service",
            "/api/v1/portfolios/7/valuation,              valuation-service",
            "/api/v1/portfolios/7/valuation/history,      valuation-service",
            "/api/v1/portfolios/7/cash,                   valuation-service",
            "/api/v1/portfolios/7/liabilities,            valuation-service",
            "/api/v1/portfolios/7/exposure,               valuation-service",
            "/api/v1/marketdata/ingestions,               valuation-service",
            "/api/v1/trades/1001/cancel,                  trade-service",
            "/api/v1/recon/breaks,                        reconciliation-service",
            "/api/v1/notifications,                       notification-service",
            "/api/v1/analytics/break-trend,               analytics-service",
            "/api/v1/ping/portfolio,                      ping-portfolio"
    })
    void routesEachPathToItsOwningService(String path, String expectedRouteId) {
        assertThat(firstMatchingRouteId(path)).isEqualTo(expectedRouteId);
    }

    @Test
    void unknownPathsAreNotRouted() {
        assertThat(firstMatchingRouteId("/api/v1/unknown")).isNull();
        assertThat(firstMatchingRouteId("/actuator/health")).isNull();
    }

    @Test
    void routesUseServiceDiscovery() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).isNotEmpty().allSatisfy(route -> assertThat(route.getUri().getScheme()).isEqualTo("lb"));
    }

    private String firstMatchingRouteId(String path) {
        return routeLocator.getRoutes()
                .concatMap(route -> {
                    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
                    return Mono.from(route.getPredicate().apply(exchange))
                            .filter(Boolean::booleanValue)
                            .map(matched -> route.getId());
                })
                .next()
                .blockOptional()
                .orElse(null);
    }
}
