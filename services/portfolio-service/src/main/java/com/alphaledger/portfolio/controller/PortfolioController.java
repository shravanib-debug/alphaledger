package com.alphaledger.portfolio.controller;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alphaledger.common.security.AuthenticatedUser;
import com.alphaledger.common.web.PageResponse;
import com.alphaledger.portfolio.domain.PortfolioStatus;
import com.alphaledger.portfolio.dto.FundRequest;
import com.alphaledger.portfolio.dto.PortfolioRequest;
import com.alphaledger.portfolio.dto.PortfolioResponse;
import com.alphaledger.portfolio.service.PortfolioService;

/** Portfolios and their funds. Any signed-in role can read; Admin and PM can change (PM: own portfolios only). */
@RestController
@RequestMapping("/api/v1/portfolios")
public class PortfolioController {

    private final PortfolioService service;

    public PortfolioController(PortfolioService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<PortfolioResponse> list(@RequestParam(required = false) PortfolioStatus status,
                                                @RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/{id}")
    public PortfolioResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ResponseEntity<PortfolioResponse> create(@Valid @RequestBody PortfolioRequest request,
                                                    Authentication authentication) {
        PortfolioResponse created = service.create(request, AuthenticatedUser.from(authentication));
        return ResponseEntity.created(URI.create("/api/v1/portfolios/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public PortfolioResponse update(@PathVariable long id, @Valid @RequestBody PortfolioRequest request,
                                    Authentication authentication) {
        return service.update(id, request, AuthenticatedUser.from(authentication));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public PortfolioResponse archive(@PathVariable long id, Authentication authentication) {
        return service.archive(id, AuthenticatedUser.from(authentication));
    }

    @PostMapping("/{id}/funds")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    public ResponseEntity<PortfolioResponse.FundResponse> addFund(@PathVariable long id,
                                                                  @Valid @RequestBody FundRequest request,
                                                                  Authentication authentication) {
        PortfolioResponse.FundResponse fund = service.addFund(id, request, AuthenticatedUser.from(authentication));
        return ResponseEntity.created(URI.create("/api/v1/portfolios/" + id)).body(fund);
    }
}
