package com.alphaledger.valuation.marketdata;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the configured fallback order to the {@link PriceSource} beans that exist. Enforces the synthetic rule
 * at startup (prd.md FR-DATA-7): SYNTHETIC may appear only in the fallback list of the PRIVATE type, so a real
 * instrument can never be priced synthetically by configuration mistake.
 */
@Component
public class PriceSourceRegistry {

    static final String SYNTHETIC_ONLY_TYPE = "PRIVATE";
    private static final Logger log = LoggerFactory.getLogger(PriceSourceRegistry.class);

    private final Map<PriceSourceName, PriceSource> sources = new EnumMap<>(PriceSourceName.class);
    private final Map<String, List<PriceSourceName>> fallbackOrder;

    public PriceSourceRegistry(List<PriceSource> sources, MarketDataProperties properties) {
        sources.forEach(source -> this.sources.put(source.name(), source));
        this.fallbackOrder = properties.fallbackOrder();
        fallbackOrder.forEach((type, order) -> {
            if (!SYNTHETIC_ONLY_TYPE.equals(type) && order.contains(PriceSourceName.SYNTHETIC)) {
                throw new IllegalStateException("alphaledger.marketdata.fallback-order." + type
                        + " must not contain SYNTHETIC: synthetic prices are only for synthetic instruments");
            }
            order.stream().filter(name -> !this.sources.containsKey(name)).forEach(name ->
                    log.info("Price source {} (fallback for {}) is not available yet; it is skipped", name, type));
        });
    }

    /** Available sources for an instrument type, in fallback order. */
    public List<PriceSource> sourcesFor(String instrumentType) {
        List<PriceSource> result = new ArrayList<>();
        for (PriceSourceName name : fallbackOrder.getOrDefault(instrumentType, List.of())) {
            Optional.ofNullable(sources.get(name)).ifPresent(result::add);
        }
        return result;
    }

    public Optional<PriceSource> get(PriceSourceName name) {
        return Optional.ofNullable(sources.get(name));
    }

    /** All available sources in the order they first appear across the fallback lists. */
    public List<PriceSource> sourcesInOrder() {
        List<PriceSource> result = new ArrayList<>();
        fallbackOrder.values().forEach(order -> order.forEach(name -> {
            PriceSource source = sources.get(name);
            if (source != null && !result.contains(source)) {
                result.add(source);
            }
        }));
        return result;
    }
}
