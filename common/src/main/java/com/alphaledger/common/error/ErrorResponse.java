package com.alphaledger.common.error;

import java.time.Instant;

/** Standard error body returned by every service (architecture.md §7.2). */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String correlationId) {
}
