package com.alphaledger.valuation.nav;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.valuation.valuation.NavCalculator;
import com.alphaledger.valuation.valuation.ValuationRepository;
import com.alphaledger.valuation.valuation.ValuationService;

/**
 * Daily management-fee accrual (architecture.md §5.4a): {@code annual rate x market value / 365}, written to
 * {@code liability} once per portfolio and calendar day. It runs every day, weekends included, just before the
 * end-of-day valuation so that day's snapshot includes the day's fee.
 */
@Service
public class FeeAccrualService {

    private static final Logger log = LoggerFactory.getLogger(FeeAccrualService.class);

    private final LedgerRepository ledger;
    private final ValuationRepository positions;
    private final ValuationService valuations;
    private final MarketClock clock;
    private final BigDecimal annualRate;

    public FeeAccrualService(LedgerRepository ledger, ValuationRepository positions, ValuationService valuations,
                             MarketClock clock,
                             @Value("${alphaledger.valuation.management-fee-rate:0.01}") BigDecimal annualRate) {
        this.ledger = ledger;
        this.positions = positions;
        this.valuations = valuations;
        this.clock = clock;
        this.annualRate = annualRate;
    }

    @Scheduled(cron = "${alphaledger.valuation.fee-accrual-cron:0 40 23 * * *}", zone = "Asia/Kolkata")
    public void scheduled() {
        accrue(clock.today());
    }

    /** Accrues the fee for {@code date}; returns how many portfolios were charged (an accrued day is skipped). */
    @Transactional
    public int accrue(LocalDate date) {
        int written = 0;
        for (long portfolioId : positions.portfoliosWithPositions()) {
            BigDecimal marketValue = valuations.value(portfolioId, date).marketValue();
            BigDecimal fee = NavCalculator.dailyFeeAccrual(annualRate, marketValue);
            if (fee.signum() > 0 && ledger.insertFeeAccrual(portfolioId, fee, date,
                    "Management fee " + annualRate.movePointRight(2).stripTrailingZeros().toPlainString()
                            + "% p.a. on " + marketValue, clock.now())) {
                written++;
            }
        }
        log.info("Management fee accrued for {}: {} portfolios at {} a year", date, written, annualRate);
        return written;
    }
}
