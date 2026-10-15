package com.alphaledger.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Settles VALIDATED trades whose settle date has arrived (FR-TR-8). Runs on a schedule in the market time zone
 * (default every 15 minutes); does nothing on non-business days. Safe to run on several instances at once.
 */
@Component
@ConditionalOnProperty(name = "alphaledger.trade.auto-settlement.enabled", havingValue = "true", matchIfMissing = true)
public class AutoSettlementJob {

    private static final Logger log = LoggerFactory.getLogger(AutoSettlementJob.class);
    private static final int BATCH_SIZE = 200;

    private final TradeService tradeService;

    public AutoSettlementJob(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @Scheduled(cron = "${alphaledger.trade.auto-settlement.cron:0 */15 * * * *}",
            zone = "${alphaledger.market.zone:Asia/Kolkata}")
    public void run() {
        int total = 0;
        int settled;
        do {
            settled = tradeService.settleDueBatch(BATCH_SIZE);
            total += settled;
        } while (settled == BATCH_SIZE);
        if (total > 0) {
            log.info("Auto-settlement: {} trades settled", total);
        }
    }
}
