package com.alphaledger.valuation.controller;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.error.ValidationException;
import com.alphaledger.valuation.marketdata.CsvPriceFile;
import com.alphaledger.valuation.marketdata.MarketDataRepository;
import com.alphaledger.valuation.marketdata.MarketDataRepository.StoredPrice;
import com.alphaledger.valuation.marketdata.PriceIngestionService;
import com.alphaledger.valuation.marketdata.PriceIngestionService.IngestionSummary;
import com.alphaledger.valuation.marketdata.PriceQueryService;
import com.alphaledger.valuation.marketdata.PriceQueryService.LatestPrice;
import com.alphaledger.valuation.marketdata.PriceSource;
import com.alphaledger.valuation.marketdata.PriceSourceName;
import com.alphaledger.valuation.marketdata.PriceSourceRegistry;

/**
 * Market data: prices with source badges, ingestion log, holidays. Any signed-in role can read; only Admin uploads
 * prices or triggers ingestion.
 */
@RestController
@RequestMapping("/api/v1/marketdata")
public class MarketDataController {

    private final PriceQueryService prices;
    private final PriceIngestionService ingestion;
    private final PriceSourceRegistry registry;
    private final MarketDataRepository repository;
    private final MarketClock clock;

    public MarketDataController(PriceQueryService prices, PriceIngestionService ingestion,
                                PriceSourceRegistry registry, MarketDataRepository repository, MarketClock clock) {
        this.prices = prices;
        this.ingestion = ingestion;
        this.registry = registry;
        this.repository = repository;
        this.clock = clock;
    }

    /** Latest price per instrument (or one instrument) with source, data date and staleness. */
    @GetMapping("/prices/latest")
    public List<LatestPrice> latest(@RequestParam(required = false) String isin,
                                    @RequestParam(required = false) Long instrumentId,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                    LocalDate asOf) {
        if (isin == null && instrumentId == null) {
            return prices.latestForAll(asOf);
        }
        return List.of(prices.latest(instrumentId, isin, asOf));
    }

    @GetMapping("/prices")
    public List<StoredPrice> history(@RequestParam String isin,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                     LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                     LocalDate to) {
        return prices.history(isin, from, to);
    }

    /** CSV columns {@code isin,data_date,price[,source]}; see CsvPriceFile. */
    @PostMapping(path = "/prices/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public IngestionSummary upload(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ValidationException("file: is empty");
        }
        return ingestion.ingest(CsvPriceFile.parse(file.getOriginalFilename(), file.getBytes()));
    }

    public record IngestionRequest(PriceSourceName source,
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataDate) {
    }

    /** Runs one source (e.g. SYNTHETIC), or the whole daily fallback run when {@code source} is omitted. */
    @PostMapping("/ingestions")
    @PreAuthorize("hasRole('ADMIN')")
    public List<IngestionSummary> ingest(@RequestBody(required = false) IngestionRequest request) {
        LocalDate date = request == null || request.dataDate() == null ? clock.today() : request.dataDate();
        if (request == null || request.source() == null) {
            return ingestion.runDaily(date);
        }
        return List.of(ingestion.runSource(registry.get(request.source()).orElseThrow(() ->
                new ValidationException("source: " + request.source() + " is not available")), date));
    }

    public record BackfillRequest(PriceSourceName source,
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    }

    /** Re-runs the daily fallback (or one source) for each business day in a range of at most 31 days. */
    @PostMapping("/ingestions/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public List<IngestionSummary> backfill(@RequestBody BackfillRequest request) {
        if (request.from() == null || request.to() == null) {
            throw new ValidationException("from, to: are required");
        }
        if (request.to().isBefore(request.from()) || request.to().isAfter(clock.today())
                || request.from().plusDays(31).isBefore(request.to())) {
            throw new ValidationException("to: must be on or after from, not in the future, and at most 31 days later");
        }
        PriceSource source = request.source() == null ? null : registry.get(request.source()).orElseThrow(() ->
                new ValidationException("source: " + request.source() + " is not available"));
        return ingestion.backfill(source, request.from(), request.to());
    }

    @GetMapping("/ingestions")
    public List<Map<String, Object>> ingestionLog(@RequestParam(required = false) PriceSourceName source,
                                                  @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return repository.ingestionLog(source, limit);
    }

    @GetMapping("/ingestions/{id}/errors")
    public List<Map<String, Object>> ingestionErrors(@PathVariable long id) {
        return repository.ingestionErrors(id);
    }

    @GetMapping("/holidays")
    public List<Map<String, Object>> holidays(@RequestParam(required = false) Integer year) {
        return repository.holidays(year == null ? clock.today().getYear() : year);
    }
}
