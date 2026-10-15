package com.alphaledger.portfolio.dto;

import java.time.Instant;
import java.util.List;

import com.alphaledger.portfolio.domain.AssetClass;
import com.alphaledger.portfolio.domain.Fund;
import com.alphaledger.portfolio.domain.Portfolio;
import com.alphaledger.portfolio.domain.PortfolioStatus;

public record PortfolioResponse(
        Long id,
        String name,
        Long managerId,
        String baseCurrency,
        PortfolioStatus status,
        List<FundResponse> funds,
        Instant createdAt,
        Instant updatedAt) {

    public static PortfolioResponse from(Portfolio portfolio) {
        return new PortfolioResponse(portfolio.getId(), portfolio.getName(), portfolio.getManagerId(),
                portfolio.getBaseCurrency(), portfolio.getStatus(),
                portfolio.getFunds().stream().map(FundResponse::from).toList(),
                portfolio.getCreatedAt(), portfolio.getUpdatedAt());
    }

    public record FundResponse(Long id, String name, AssetClass assetClass) {

        public static FundResponse from(Fund fund) {
            return new FundResponse(fund.getId(), fund.getName(), fund.getAssetClass());
        }
    }
}
