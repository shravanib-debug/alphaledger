package com.alphaledger.portfolio.repository;

import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.alphaledger.portfolio.domain.AssetClass;
import com.alphaledger.portfolio.domain.Instrument;
import com.alphaledger.portfolio.domain.InstrumentType;

public interface InstrumentRepository extends JpaRepository<Instrument, Long>, JpaSpecificationExecutor<Instrument> {

    boolean existsByIsin(String isin);

    /** Optional filters for the instrument search; null arguments are ignored. */
    static Specification<Instrument> matching(AssetClass assetClass, InstrumentType type, String isin, String text) {
        Specification<Instrument> spec = Specification.unrestricted();
        if (assetClass != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("assetClass"), assetClass));
        }
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("instrumentType"), type));
        }
        if (isin != null && !isin.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("isin"), isin.trim().toUpperCase(Locale.ROOT)));
        }
        if (text != null && !text.isBlank()) {
            String like = "%" + text.trim().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(root.get("symbol")), like)));
        }
        return spec;
    }
}
