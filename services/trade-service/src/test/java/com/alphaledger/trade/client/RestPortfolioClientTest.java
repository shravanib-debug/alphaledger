package com.alphaledger.trade.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import com.alphaledger.common.error.DependencyUnavailableException;

/** Retry and circuit breaker on the calls to portfolio-service, against a mocked server (no real waits). */
class RestPortfolioClientTest {

    private static final String PORTFOLIO_7 = "http://portfolio-service/api/v1/portfolios/7";

    private MockRestServiceServer server;
    private RestPortfolioClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://portfolio-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestPortfolioClient(builder.build(), Duration.ofMillis(1), Duration.ofSeconds(60));
    }

    @Test
    void aTransientServerErrorIsRetried() {
        server.expect(times(2), requestTo(PORTFOLIO_7)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(PORTFOLIO_7)).andRespond(withSuccess(
                "{\"id\":7,\"name\":\"Growth\",\"managerId\":2,\"status\":\"ACTIVE\"}", MediaType.APPLICATION_JSON));

        assertThat(client.findPortfolio(7)).isPresent();
        server.verify();
    }

    @Test
    void notFoundIsAnAnswerAndIsNotRetried() {
        server.expect(times(1), requestTo(PORTFOLIO_7)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findPortfolio(7)).isEmpty();
        server.verify();
    }

    @Test
    void aForbiddenAnswerIsNotRetriedNorCountedAsAFailure() {
        server.expect(times(1), requestTo(PORTFOLIO_7)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.findPortfolio(7)).isInstanceOf(DependencyUnavailableException.class);
        server.verify();
        assertThat(client.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void repeatedFailuresOpenTheBreakerAndLaterCallsFailFastWithoutARequest() {
        // Two lookups x 3 attempts = 6 failed calls; the breaker opens after 5 (half of them failed).
        server.expect(times(5), requestTo(PORTFOLIO_7)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.findPortfolio(7)).isInstanceOf(DependencyUnavailableException.class);
        assertThatThrownBy(() -> client.findPortfolio(7)).isInstanceOf(DependencyUnavailableException.class)
                .hasMessageContaining("circuit open");
        assertThat(client.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> client.findPortfolio(7)).hasMessageContaining("circuit open");
        server.verify();   // exactly 5 requests reached the server
    }
}
