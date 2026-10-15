package com.alphaledger.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param portfolioServiceUrl base URL of portfolio-service; {@code http://portfolio-service} resolves through Eureka
 * @param settlementDays     settlement cycle in business days (T+1 for Indian equities; prd.md business rule 9)
 * @param kafkaReplicas      replication factor for the topics this service creates (1 for a single local broker)
 */
@ConfigurationProperties("alphaledger.trade")
public record TradeProperties(String portfolioServiceUrl, Integer settlementDays, Integer kafkaReplicas) {

    public TradeProperties {
        portfolioServiceUrl = portfolioServiceUrl == null ? "http://portfolio-service" : portfolioServiceUrl;
        settlementDays = settlementDays == null ? 1 : settlementDays;
        kafkaReplicas = kafkaReplicas == null ? 1 : kafkaReplicas;
    }
}
