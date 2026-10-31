package com.alphaledger.reconciliation.run;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

import org.springframework.batch.infrastructure.item.ItemProcessor;

import com.alphaledger.common.csv.CsvLine;
import com.alphaledger.common.instrument.Isin;
import com.alphaledger.reconciliation.matching.MatchingEngine.ExternalRecord;

/**
 * Validates custodian rows (FR-REC-2): {@code trade_ref,isin,trade_date,settle_date,side,quantity,price}. A bad row is
 * not dropped silently: it becomes a {@code custodian_record_error} with the reason. Stateless, so partitions can run
 * in parallel; duplicate match keys are rejected afterwards by the {@code flagDuplicates} step.
 */
public class CustodianRowValidator implements ItemProcessor<StagedRow.RawRow, StagedRow> {

    public static final String HEADER = "trade_ref,isin,trade_date,settle_date,side,quantity,price";

    private final long runId;
    private final LocalDate runDate;

    public CustodianRowValidator(long runId, LocalDate runDate) {
        this.runId = runId;
        this.runDate = runDate;
    }

    @Override
    public StagedRow process(StagedRow.RawRow row) {
        String raw = row.line().length() > 1000 ? row.line().substring(0, 1000) : row.line();
        List<String> f = CsvLine.split(row.line());
        if (f.size() != 7) {
            return StagedRow.rejected(runId, row.rowNumber(), "expected 7 columns, found " + f.size(), raw);
        }
        String tradeRef = f.get(0).trim();
        String isin = Isin.normalize(f.get(1));
        String side = f.get(4).trim().toUpperCase(Locale.ROOT);
        if (tradeRef.isEmpty() || tradeRef.length() > 40) {
            return StagedRow.rejected(runId, row.rowNumber(), "trade_ref: required, at most 40 characters", raw);
        }
        if (!Isin.isValid(isin) && !(Isin.isSynthetic(isin) && Isin.hasValidFormat(isin))) {
            return StagedRow.rejected(runId, row.rowNumber(), "isin: not a valid ISIN", raw);
        }
        if (!side.equals("BUY") && !side.equals("SELL")) {
            return StagedRow.rejected(runId, row.rowNumber(), "side: must be BUY or SELL", raw);
        }
        LocalDate tradeDate;
        LocalDate settleDate;
        BigDecimal quantity;
        BigDecimal price;
        try {
            tradeDate = LocalDate.parse(f.get(2).trim());
            settleDate = LocalDate.parse(f.get(3).trim());
        } catch (DateTimeParseException ex) {
            return StagedRow.rejected(runId, row.rowNumber(), "dates: must be yyyy-MM-dd", raw);
        }
        try {
            quantity = new BigDecimal(f.get(5).trim());
            price = new BigDecimal(f.get(6).trim());
        } catch (NumberFormatException ex) {
            return StagedRow.rejected(runId, row.rowNumber(), "quantity and price: must be numbers", raw);
        }
        if (quantity.signum() <= 0 || price.signum() <= 0) {
            return StagedRow.rejected(runId, row.rowNumber(), "quantity and price: must be greater than zero", raw);
        }
        if (!settleDate.equals(runDate)) {
            return StagedRow.rejected(runId, row.rowNumber(),
                    "settle_date " + settleDate + " is not the run date " + runDate, raw);
        }
        return StagedRow.valid(runId, row.rowNumber(),
                new ExternalRecord(row.rowNumber(), tradeRef, isin, tradeDate, side, quantity, price), settleDate, raw);
    }
}
