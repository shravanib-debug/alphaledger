package com.alphaledger.trade.dto;

import java.util.List;

/**
 * Result of a CSV upload. Each row is processed on its own: {@code validated} and {@code rejected} rows are stored,
 * {@code replayed} rows matched an existing trade reference, {@code failed} rows were not stored (see errors).
 */
public record BulkUploadResponse(int total, int validated, int rejected, int replayed, int failed,
                                 List<RowError> errors) {

    public record RowError(int line, String message) {
    }
}
