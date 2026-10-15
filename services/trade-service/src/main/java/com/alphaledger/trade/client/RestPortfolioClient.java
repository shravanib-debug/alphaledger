package com.alphaledger.trade.client;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.alphaledger.common.error.DependencyUnavailableException;

/**
 * Calls portfolio-service through Eureka ({@code lb://portfolio-service}) with the caller's own access token,
 * so portfolio-service applies the same authorization as for a direct call.
 */
@Component
public class RestPortfolioClient implements PortfolioClient {

    private final RestClient restClient;

    public RestPortfolioClient(@Qualifier("portfolioRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<PortfolioInfo> findPortfolio(long portfolioId) {
        try {
            return Optional.ofNullable(restClient.get().uri("/api/v1/portfolios/{id}", portfolioId)
                    .retrieve().body(PortfolioInfo.class));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            throw unavailable(ex);
        } catch (RestClientException ex) {
            throw unavailable(ex);
        }
    }

    @Override
    public Optional<InstrumentInfo> findInstrumentByIsin(String isin) {
        try {
            InstrumentPage page = restClient.get().uri("/api/v1/instruments?isin={isin}", isin)
                    .retrieve().body(InstrumentPage.class);
            return page == null || page.content() == null ? Optional.empty() : page.content().stream().findFirst();
        } catch (RestClientException ex) {
            throw unavailable(ex);
        }
    }

    private static DependencyUnavailableException unavailable(Exception cause) {
        return new DependencyUnavailableException("portfolio-service is unavailable: " + cause.getMessage());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstrumentPage(List<InstrumentInfo> content) {
    }
}
