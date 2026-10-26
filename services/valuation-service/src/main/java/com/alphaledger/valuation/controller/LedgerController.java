package com.alphaledger.valuation.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.security.AuthenticatedUser;
import com.alphaledger.valuation.nav.FeeAccrualService;
import com.alphaledger.valuation.nav.LedgerService;
import com.alphaledger.valuation.nav.LedgerService.CashSummary;

/**
 * Cash and liabilities (FR-VAL-8, FR-VAL-9). Any signed-in role can read; Admin and Ops record cash movements and
 * liabilities, as fund accounting is a back-office task.
 */
@RestController
public class LedgerController {

    private final LedgerService ledger;
    private final FeeAccrualService feeAccrual;
    private final MarketClock clock;

    public LedgerController(LedgerService ledger, FeeAccrualService feeAccrual, MarketClock clock) {
        this.ledger = ledger;
        this.feeAccrual = feeAccrual;
        this.clock = clock;
    }

    @GetMapping("/api/v1/portfolios/{portfolioId}/cash")
    public CashSummary cash(@PathVariable long portfolioId) {
        return ledger.cash(portfolioId);
    }

    /** {@code effectiveDate} defaults to today. */
    public record CashMovementRequest(@NotNull @Pattern(regexp = "DEPOSIT|WITHDRAWAL") String type,
                                      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 22, fraction = 2)
                                      BigDecimal amount,
                                      @Size(max = 200) String description,
                                      LocalDate effectiveDate) {
    }

    @PostMapping("/api/v1/portfolios/{portfolioId}/cash")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS_ANALYST')")
    @ResponseStatus(HttpStatus.CREATED)
    public CashSummary moveCash(@PathVariable long portfolioId, @Valid @RequestBody CashMovementRequest request,
                                Authentication authentication) {
        return ledger.moveCash(portfolioId, request.type(), request.amount(), request.description(),
                request.effectiveDate(), AuthenticatedUser.from(authentication).id());
    }

    @GetMapping("/api/v1/portfolios/{portfolioId}/liabilities")
    public List<Map<String, Object>> liabilities(@PathVariable long portfolioId,
                                                 @RequestParam(required = false) @Pattern(regexp = "OPEN|PAID")
                                                 String status) {
        return ledger.liabilities(portfolioId, status);
    }

    /** {@code accrualDate} defaults to today. A BORROWING also brings its amount into cash. */
    public record LiabilityRequest(@NotNull @Pattern(regexp = "MANAGEMENT_FEE|EXPENSE|PAYABLE|BORROWING") String type,
                                   @NotBlank @Size(max = 200) String description,
                                   @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 22, fraction = 2)
                                   BigDecimal amount,
                                   LocalDate accrualDate) {
    }

    @PostMapping("/api/v1/portfolios/{portfolioId}/liabilities")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS_ANALYST')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addLiability(@PathVariable long portfolioId, @Valid @RequestBody LiabilityRequest request,
                                            Authentication authentication) {
        return ledger.addLiability(portfolioId, request.type(), request.description(), request.amount(),
                request.accrualDate(), AuthenticatedUser.from(authentication).id());
    }

    public record LiabilityUpdateRequest(@NotBlank @Size(max = 200) String description,
                                         @NotNull @DecimalMin(value = "0", inclusive = false)
                                         @Digits(integer = 22, fraction = 2) BigDecimal amount,
                                         LocalDate accrualDate) {
    }

    @PutMapping("/api/v1/portfolios/{portfolioId}/liabilities/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS_ANALYST')")
    public Map<String, Object> updateLiability(@PathVariable long portfolioId, @PathVariable long id,
                                               @Valid @RequestBody LiabilityUpdateRequest request) {
        return ledger.updateLiability(portfolioId, id, request.description(), request.amount(), request.accrualDate());
    }

    /** Pays the liability out of cash; {@code date} defaults to today. */
    @PostMapping("/api/v1/portfolios/{portfolioId}/liabilities/{id}/pay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS_ANALYST')")
    public Map<String, Object> payLiability(@PathVariable long portfolioId, @PathVariable long id,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                            LocalDate date, Authentication authentication) {
        return ledger.payLiability(portfolioId, id, date, AuthenticatedUser.from(authentication).id());
    }

    @DeleteMapping("/api/v1/portfolios/{portfolioId}/liabilities/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS_ANALYST')")
    public ResponseEntity<Void> deleteLiability(@PathVariable long portfolioId, @PathVariable long id) {
        ledger.deleteLiability(portfolioId, id);
        return ResponseEntity.noContent().build();
    }

    /** Runs the management-fee accrual for a date (default today); an accrued day is not charged again. */
    @PostMapping("/api/v1/valuations/fee-accruals")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> accrueFees(@RequestParam(required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate runDate = date == null ? clock.today() : date;
        return Map.of("accrualDate", runDate.toString(), "portfoliosCharged", feeAccrual.accrue(runDate));
    }
}
