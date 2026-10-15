package com.alphaledger.portfolio.domain;

/** The listed or synthetic stand-in used for an asset class (prd.md §4.10, instrument universe). */
public enum AssetClassProxy {
    LISTED_REIT,
    LISTED_INVIT,
    GOVERNMENT_SECURITY,
    CORPORATE_BOND,
    DEBT_MUTUAL_FUND,
    LISTED_EQUITY,
    EQUITY_MUTUAL_FUND,
    SYNTHETIC_PRIVATE
}
