package com.alphaledger.valuation.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.error.NotFoundException;
import com.alphaledger.valuation.valuation.NavCalculator.Nav;
import com.alphaledger.valuation.valuation.ValuationCalculator.Exposure;
import com.alphaledger.valuation.valuation.ValuationCalculator.PortfolioValuation;
import com.alphaledger.valuation.valuation.ValuationJobConfig.EndOfDayValuation;
import com.alphaledger.valuation.valuation.ValuationRepository;
import com.alphaledger.valuation.valuation.ValuationService;

/**
 * Portfolio valuation: NAV = market value + cash - liabilities, with each component shown (FR-VAL-10). Any signed-in role can read; Admin
 * manages limit rules and can run the end-of-day valuation for a date.
 */
@RestController
public class ValuationController {

    private final ValuationService valuations;
    private final ValuationRepository repository;
    private final EndOfDayValuation endOfDay;
    private final MarketClock clock;

    public ValuationController(ValuationService valuations, ValuationRepository repository,
                               EndOfDayValuation endOfDay, MarketClock clock) {
        this.valuations = valuations;
        this.repository = repository;
        this.endOfDay = endOfDay;
        this.clock = clock;
    }

    public record ValuationResponse(long portfolioId, LocalDate asOfDate, String valuationBasis, BigDecimal marketValue,
                                    BigDecimal cashBalance, BigDecimal totalLiabilities, BigDecimal nav,
                                    PortfolioValuation details) {
    }

    /** Live valuation from the latest positions and prices; each line shows its price source, date and staleness. */
    @GetMapping("/api/v1/portfolios/{portfolioId}/valuation")
    public ValuationResponse valuation(@PathVariable long portfolioId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                       LocalDate asOf) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        PortfolioValuation v = valuations.value(portfolioId, date);
        Nav nav = valuations.nav(portfolioId, date, v);
        return new ValuationResponse(portfolioId, date, nav.basis(), nav.marketValue(), nav.cash(), nav.liabilities(),
                nav.nav(), v);
    }

    @GetMapping("/api/v1/portfolios/{portfolioId}/valuation/history")
    public List<Map<String, Object>> history(@PathVariable long portfolioId,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                             LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                             LocalDate to) {
        return valuations.history(portfolioId, from, to);
    }

    public record ExposureResponse(long portfolioId, BigDecimal marketValue, List<Exposure> byAssetClass,
                                   List<Exposure> byInstrument) {
    }

    @GetMapping("/api/v1/portfolios/{portfolioId}/exposure")
    public ExposureResponse exposure(@PathVariable long portfolioId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                     LocalDate asOf) {
        PortfolioValuation v = valuations.value(portfolioId, asOf);
        return new ExposureResponse(portfolioId, v.marketValue(), v.exposureByAssetClass(), v.exposureByInstrument());
    }

    @GetMapping("/api/v1/valuations/limit-rules")
    public List<Map<String, Object>> limitRules() {
        return repository.limitRules();
    }

    /** {@code portfolioId} omitted = the rule applies to every portfolio; a portfolio rule overrides it. */
    public record LimitRuleRequest(Long portfolioId,
                                   @NotNull @Pattern(regexp = "PRIVATE_EQUITY|CREDIT|REAL_ESTATE") String assetClass,
                                   @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal maxPct) {
    }

    @PostMapping("/api/v1/valuations/limit-rules")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> upsertLimitRule(@Valid @RequestBody LimitRuleRequest request) {
        return repository.upsertLimitRule(request.portfolioId(), request.assetClass(), request.maxPct());
    }

    @DeleteMapping("/api/v1/valuations/limit-rules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteLimitRule(@PathVariable long id) {
        if (repository.deleteLimitRule(id) == 0) {
            throw new NotFoundException("Limit rule " + id + " not found");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/valuations/limit-breaches")
    public List<Map<String, Object>> breaches(@RequestParam(required = false) Long portfolioId) {
        return repository.breaches(portfolioId);
    }

    /** Runs the end-of-day valuation job for a date (default today); a completed date is not run again. */
    @PostMapping("/api/v1/valuations/eod-runs")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> runEndOfDay(@RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate runDate = date == null ? clock.today() : date;
        return Map.of("valuationDate", runDate.toString(), "status", endOfDay.run(runDate));
    }
}
