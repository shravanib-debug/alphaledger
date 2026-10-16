package com.alphaledger.position.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alphaledger.position.repository.PositionRepository.PositionRow;
import com.alphaledger.position.service.PositionService;

/** Holdings per portfolio (FR-POS-4). Any signed-in role can read. */
@RestController
@RequestMapping("/api/v1/portfolios/{portfolioId}/positions")
public class PositionController {

    private final PositionService service;

    public PositionController(PositionService service) {
        this.service = service;
    }

    /** Current holdings; closed positions (quantity 0, realized P&L only) when {@code includeClosed=true}. */
    @GetMapping
    public List<PositionRow> positions(@PathVariable long portfolioId,
                                       @RequestParam(defaultValue = "false") boolean includeClosed) {
        return service.positions(portfolioId, includeClosed);
    }

    /** End-of-day snapshot for a date (FR-POS-6). */
    @GetMapping("/snapshots")
    public List<PositionRow> snapshot(@PathVariable long portfolioId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.snapshot(portfolioId, date);
    }

    /** Trades that could not be applied, e.g. a SELL above the holding. */
    @GetMapping("/exceptions")
    public List<Map<String, Object>> exceptions(@PathVariable long portfolioId) {
        return service.exceptions(portfolioId);
    }
}
