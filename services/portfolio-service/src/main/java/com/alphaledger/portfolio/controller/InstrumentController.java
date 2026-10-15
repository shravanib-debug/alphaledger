package com.alphaledger.portfolio.controller;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alphaledger.common.web.PageResponse;
import com.alphaledger.portfolio.domain.AssetClass;
import com.alphaledger.portfolio.domain.InstrumentType;
import com.alphaledger.portfolio.dto.CreateInstrumentRequest;
import com.alphaledger.portfolio.dto.InstrumentResponse;
import com.alphaledger.portfolio.dto.UpdateInstrumentRequest;
import com.alphaledger.portfolio.service.InstrumentService;

/** Instrument master. Any signed-in role can read; Admin and PM can add or edit instruments. */
@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentController {

    private final InstrumentService service;

    public InstrumentController(InstrumentService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<InstrumentResponse> search(@RequestParam(required = false) AssetClass assetClass,
                                                   @RequestParam(required = false) InstrumentType instrumentType,
                                                   @RequestParam(required = false) String isin,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                                   @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return service.search(assetClass, instrumentType, isin, q, page, size);
    }

    @GetMapping("/{id}")
    public InstrumentResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ResponseEntity<InstrumentResponse> create(@Valid @RequestBody CreateInstrumentRequest request) {
        InstrumentResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/instruments/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public InstrumentResponse update(@PathVariable long id, @Valid @RequestBody UpdateInstrumentRequest request) {
        return service.update(id, request);
    }
}
