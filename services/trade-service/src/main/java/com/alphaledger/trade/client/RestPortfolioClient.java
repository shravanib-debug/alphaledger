package com.alphaledger.trade.client;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import com.alphaledger.common.error.DependencyUnavailableException;

/**
 * Calls portfolio-service through Eureka ({@code lb://portfolio-service}) with the caller's own access token,
 * so portfolio-service applies the same authorization as for a direct call.
 * <p>
 * Both calls are reads, so a connection error or 5xx is retried (3 attempts, 200 ms then 400 ms). A 4xx is an answer,
 * not a failure, and is never retried. A circuit breaker stops calling for 10 s once half of the last 10 calls
 * failed, so trade capture fails fast with 503 instead of waiting on a dead service.
 */
@Component
public class RestPortfolioClient implements PortfolioClient {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    @Autowired
    public RestPortfolioClient(@Qualifier("portfolioRestClient") RestClient restClient) {
        this(restClient, Duration.ofMillis(200), Duration.ofSeconds(10));
    }

    RestPortfolioClient(RestClient restClient, Duration firstBackoff, Duration openFor) {
        this.restClient = restClient;
        this.circuitBreaker = CircuitBreaker.of("portfolio-service", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(openFor)
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(ClientError.class)
                .build());
        this.retry = Retry.of("portfolio-service", RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(firstBackoff, 2))
                .retryOnException(ex -> ex instanceof RestClientException && !(ex instanceof ClientError))
                .build());
    }

    @Override
    public Optional<PortfolioInfo> findPortfolio(long portfolioId) {
        try {
            return Optional.ofNullable(call(() -> restClient.get().uri("/api/v1/portfolios/{id}", portfolioId)
                    .retrieve().body(PortfolioInfo.class)));
        } catch (ClientError ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            throw unavailable(ex);
        }
    }

    @Override
    public Optional<InstrumentInfo> findInstrumentByIsin(String isin) {
        try {
            InstrumentPage page = call(() -> restClient.get().uri("/api/v1/instruments?isin={isin}", isin)
                    .retrieve().body(InstrumentPage.class));
            return page == null || page.content() == null ? Optional.empty() : page.content().stream().findFirst();
        } catch (ClientError ex) {
            throw unavailable(ex);
        }
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    /** Runs the call with retry around the breaker (each attempt counts); 4xx come back as {@link ClientError}. */
    private <T> T call(Supplier<T> request) {
        Supplier<T> asClientError = () -> {
            try {
                return request.get();
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().is4xxClientError()) {
                    throw new ClientError(ex);
                }
                throw ex;
            }
        };
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, asClientError)).get();
        } catch (CallNotPermittedException ex) {
            throw new DependencyUnavailableException("portfolio-service is unavailable (circuit open); try again shortly");
        } catch (ClientError ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw unavailable(ex);
        }
    }

    private static DependencyUnavailableException unavailable(Exception cause) {
        return new DependencyUnavailableException("portfolio-service is unavailable: " + cause.getMessage());
    }

    /** A 4xx answer: not a failure of portfolio-service, so neither retried nor counted by the breaker. */
    static final class ClientError extends RestClientException {

        private final RestClientResponseException response;

        ClientError(RestClientResponseException response) {
            super(response.getMessage(), response);
            this.response = response;
        }

        org.springframework.http.HttpStatusCode getStatusCode() {
            return response.getStatusCode();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstrumentPage(List<InstrumentInfo> content) {
    }
}
