package com.alphaledger.trade.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.alphaledger.common.error.ApiException;
import com.alphaledger.common.error.ValidationException;
import com.alphaledger.common.security.AuthenticatedUser;
import com.alphaledger.trade.domain.TradeSide;
import com.alphaledger.trade.domain.TradeStatus;
import com.alphaledger.trade.dto.BulkUploadResponse;
import com.alphaledger.trade.dto.CreateTradeRequest;

/**
 * Bulk trade upload (FR-TR-7). Each row goes through the same path as a single create, in its own transaction,
 * so one bad row never undoes the others. Columns:
 * {@code trade_ref,portfolio_id,isin,side,quantity,price,trade_date,settle_date} (trade_ref, trade_date and
 * settle_date may be empty).
 */
@Component
public class TradeCsvImporter {

    static final String HEADER = "trade_ref,portfolio_id,isin,side,quantity,price,trade_date,settle_date";
    static final int MAX_ROWS = 1000;

    private final TradeService tradeService;
    private final Validator validator;

    public TradeCsvImporter(TradeService tradeService, Validator validator) {
        this.tradeService = tradeService;
        this.validator = validator;
    }

    public BulkUploadResponse importCsv(InputStream csv, AuthenticatedUser user) throws IOException {
        int validated = 0;
        int rejected = 0;
        int replayed = 0;
        int total = 0;
        List<BulkUploadResponse.RowError> errors = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !HEADER.equals(header.strip().replace("﻿", "").toLowerCase(Locale.ROOT))) {
                throw new ValidationException("file: first line must be the header '" + HEADER + "'");
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                if (++total > MAX_ROWS) {
                    throw new ValidationException("file: at most " + MAX_ROWS + " trades per upload");
                }
                try {
                    TradeService.CreateResult result = tradeService.create(parse(line), user);
                    if (result.replayed()) {
                        replayed++;
                    } else if (result.trade().status() == TradeStatus.REJECTED) {
                        rejected++;
                    } else {
                        validated++;
                    }
                } catch (ApiException | AccessDeniedException | IllegalArgumentException ex) {
                    errors.add(new BulkUploadResponse.RowError(lineNumber, ex.getMessage()));
                }
            }
        }
        return new BulkUploadResponse(total, validated, rejected, replayed, errors.size(), errors);
    }

    private CreateTradeRequest parse(String line) {
        String[] f = line.split(",", -1);
        if (f.length != 8) {
            throw new IllegalArgumentException("expected 8 columns, found " + f.length);
        }
        CreateTradeRequest request;
        try {
            request = new CreateTradeRequest(blankToNull(f[0]), Long.valueOf(f[1].trim()), f[2].trim(),
                    TradeSide.valueOf(f[3].trim().toUpperCase(Locale.ROOT)), new BigDecimal(f[4].trim()),
                    new BigDecimal(f[5].trim()), date(f[6]), date(f[7]));
        } catch (NumberFormatException | DateTimeParseException ex) {
            throw new IllegalArgumentException("invalid number or date: " + ex.getMessage());
        }
        Set<ConstraintViolation<CreateTradeRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().reduce((a, b) -> a + "; " + b)
                    .orElse("invalid row"));
        }
        return request;
    }

    private static LocalDate date(String value) {
        return value.isBlank() ? null : LocalDate.parse(value.trim());
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value.trim();
    }
}
