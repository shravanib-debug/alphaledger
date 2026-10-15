package com.alphaledger.portfolio.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.portfolio.domain.AssetClass;

/**
 * Seeds the three demo portfolios and their funds (prd.md §10) with fixed IDs, managed by the demo PM
 * (Priya, user ID 2 in auth-service). Local and demo environments only; existing rows are left untouched.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "alphaledger.seed.demo-data.enabled", havingValue = "true")
public class DemoPortfolioSeeder implements ApplicationRunner {

    static final long DEMO_PM_USER_ID = 2;
    private static final Logger log = LoggerFactory.getLogger(DemoPortfolioSeeder.class);

    static final List<DemoPortfolio> PORTFOLIOS = List.of(
            new DemoPortfolio(1, "AlphaLedger India Credit Opportunities Fund I", List.of(
                    new DemoFund(1, "Listed Credit Sleeve", AssetClass.CREDIT),
                    new DemoFund(2, "Debt Mutual Fund Sleeve", AssetClass.CREDIT))),
            new DemoPortfolio(2, "AlphaLedger Bharat Real Assets Fund", List.of(
                    new DemoFund(3, "REIT Sleeve", AssetClass.REAL_ESTATE),
                    new DemoFund(4, "InvIT Sleeve", AssetClass.REAL_ESTATE))),
            new DemoPortfolio(3, "AlphaLedger India Growth Equity Fund", List.of(
                    new DemoFund(5, "Listed Equity Proxy Sleeve", AssetClass.PRIVATE_EQUITY),
                    new DemoFund(6, "Equity Mutual Fund Sleeve", AssetClass.PRIVATE_EQUITY),
                    new DemoFund(7, "Private Holdings (Synthetic)", AssetClass.PRIVATE_EQUITY))));

    private final JdbcTemplate jdbc;

    public DemoPortfolioSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int created = 0;
        for (DemoPortfolio p : PORTFOLIOS) {
            created += jdbc.update("""
                    INSERT INTO portfolio (id, name, manager_id, base_currency, status)
                    VALUES (?, ?, ?, 'INR', 'ACTIVE') ON CONFLICT DO NOTHING
                    """, p.id(), p.name(), DEMO_PM_USER_ID);
            for (DemoFund f : p.funds()) {
                jdbc.update("INSERT INTO fund (id, portfolio_id, name, asset_class) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT DO NOTHING", f.id(), p.id(), f.name(), f.assetClass().name());
            }
        }
        log.info("Demo portfolios: {} created, {} already present", created, PORTFOLIOS.size() - created);
    }

    record DemoPortfolio(long id, String name, List<DemoFund> funds) {
    }

    record DemoFund(long id, String name, AssetClass assetClass) {
    }
}
