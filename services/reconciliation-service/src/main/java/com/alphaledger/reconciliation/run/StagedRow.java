package com.alphaledger.reconciliation.run;

import java.time.LocalDate;

import com.alphaledger.reconciliation.matching.MatchingEngine.ExternalRecord;

/** One custodian file row after validation: either a usable record or the reason it was rejected. */
public record StagedRow(long runId, int rowNumber, ExternalRecord record, LocalDate settleDate, String error,
                        String rawRow) {

    public static StagedRow valid(long runId, int rowNumber, ExternalRecord record, LocalDate settleDate, String raw) {
        return new StagedRow(runId, rowNumber, record, settleDate, null, raw);
    }

    public static StagedRow rejected(long runId, int rowNumber, String error, String raw) {
        return new StagedRow(runId, rowNumber, null, null, error, raw);
    }

    /** A raw line read from the file, before validation. */
    public record RawRow(int rowNumber, String line) {
    }
}
