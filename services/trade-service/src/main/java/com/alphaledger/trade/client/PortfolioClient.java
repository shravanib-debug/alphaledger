package com.alphaledger.trade.client;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Read-only view of portfolio-service needed to validate trades (ADR 15: synchronous validation call). */
public interface PortfolioClient {

    Optional<PortfolioInfo> findPortfolio(long portfolioId);

    Optional<InstrumentInfo> findInstrumentByIsin(String isin);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PortfolioInfo(long id, String name, long managerId, String status) {

        public boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstrumentInfo(long id, String isin, String name, String instrumentType, String status) {

        public boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }
}
