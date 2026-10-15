package com.alphaledger.trade.controller;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.alphaledger.common.error.ValidationException;
import com.alphaledger.common.security.AuthenticatedUser;
import com.alphaledger.common.web.PageResponse;
import com.alphaledger.trade.domain.TradeSide;
import com.alphaledger.trade.domain.TradeStatus;
import com.alphaledger.trade.dto.BulkUploadResponse;
import com.alphaledger.trade.dto.CreateTradeRequest;
import com.alphaledger.trade.dto.StatusChangeRequest;
import com.alphaledger.trade.dto.TradeAuditResponse;
import com.alphaledger.trade.dto.TradeResponse;
import com.alphaledger.trade.service.TradeCsvImporter;
import com.alphaledger.trade.service.TradeService;

/**
 * Trades. Any signed-in role can read the blotter; Admin and PM capture and cancel trades (PM: own portfolios);
 * only Admin settles manually.
 */
@RestController
@RequestMapping("/api/v1/trades")
public class TradeController {

    private final TradeService service;
    private final TradeCsvImporter importer;

    public TradeController(TradeService service, TradeCsvImporter importer) {
        this.service = service;
        this.importer = importer;
    }

    /**
     * 201 with the new trade (status VALIDATED, or REJECTED with a reason); 200 with the existing trade when the
     * same tradeRef is sent again with the same details.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ResponseEntity<TradeResponse> create(@Valid @RequestBody CreateTradeRequest request,
                                                Authentication authentication) {
        TradeService.CreateResult result = service.create(request, AuthenticatedUser.from(authentication));
        if (result.replayed()) {
            return ResponseEntity.ok(result.trade());
        }
        return ResponseEntity.created(URI.create("/api/v1/trades/" + result.trade().id())).body(result.trade());
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public BulkUploadResponse upload(@RequestPart("file") MultipartFile file, Authentication authentication)
            throws IOException {
        if (file.isEmpty()) {
            throw new ValidationException("file: is empty");
        }
        return importer.importCsv(file.getInputStream(), AuthenticatedUser.from(authentication));
    }

    @GetMapping
    public PageResponse<TradeResponse> blotter(
            @RequestParam(required = false) Long portfolioId,
            @RequestParam(required = false) TradeStatus status,
            @RequestParam(required = false) String isin,
            @RequestParam(required = false) TradeSide side,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return service.blotter(portfolioId, status, isin, side, from, to, page, size);
    }

    @GetMapping("/{id}")
    public TradeResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/audit")
    public List<TradeAuditResponse> audit(@PathVariable long id) {
        return service.auditTrail(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public TradeResponse cancel(@PathVariable long id, @Valid @RequestBody(required = false) StatusChangeRequest request,
                                Authentication authentication) {
        return service.cancel(id, request == null ? null : request.comment(), AuthenticatedUser.from(authentication));
    }

    @PostMapping("/{id}/settle")
    @PreAuthorize("hasRole('ADMIN')")
    public TradeResponse settle(@PathVariable long id, @Valid @RequestBody(required = false) StatusChangeRequest request,
                                Authentication authentication) {
        return service.settle(id, request == null ? null : request.comment(), AuthenticatedUser.from(authentication));
    }
}
