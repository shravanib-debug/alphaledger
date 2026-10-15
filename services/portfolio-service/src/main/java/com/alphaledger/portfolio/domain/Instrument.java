package com.alphaledger.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A tradable instrument, keyed by ISIN (symbol is secondary). Reference instruments are seeded with fixed IDs. */
@Entity
@Table(name = "instrument")
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private String isin;

    private String symbol;

    private String exchange;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", nullable = false)
    private AssetClass assetClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class_proxy", nullable = false)
    private AssetClassProxy assetClassProxy;

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_type", nullable = false)
    private InstrumentType instrumentType;

    @Column(nullable = false)
    private String currency;

    @Column(name = "amfi_scheme_code")
    private String amfiSchemeCode;

    @Column(name = "is_synthetic", nullable = false)
    private boolean synthetic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstrumentStatus status;

    protected Instrument() {
    }

    public Instrument(String isin, String symbol, String exchange, String name, AssetClass assetClass,
                      AssetClassProxy assetClassProxy, InstrumentType instrumentType, String amfiSchemeCode,
                      boolean synthetic) {
        this.isin = isin;
        this.symbol = symbol;
        this.exchange = exchange;
        this.name = name;
        this.assetClass = assetClass;
        this.assetClassProxy = assetClassProxy;
        this.instrumentType = instrumentType;
        this.currency = "INR";
        this.amfiSchemeCode = amfiSchemeCode;
        this.synthetic = synthetic;
        this.status = InstrumentStatus.ACTIVE;
    }

    /** Updates the descriptive fields; ISIN, type and asset class are fixed once created. */
    public void update(String symbol, String exchange, String name, String amfiSchemeCode, InstrumentStatus status) {
        this.symbol = symbol;
        this.exchange = exchange;
        this.name = name;
        this.amfiSchemeCode = amfiSchemeCode;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getIsin() {
        return isin;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getExchange() {
        return exchange;
    }

    public String getName() {
        return name;
    }

    public AssetClass getAssetClass() {
        return assetClass;
    }

    public AssetClassProxy getAssetClassProxy() {
        return assetClassProxy;
    }

    public InstrumentType getInstrumentType() {
        return instrumentType;
    }

    public String getCurrency() {
        return currency;
    }

    public String getAmfiSchemeCode() {
        return amfiSchemeCode;
    }

    public boolean isSynthetic() {
        return synthetic;
    }

    public InstrumentStatus getStatus() {
        return status;
    }
}
