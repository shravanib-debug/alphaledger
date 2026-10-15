package com.alphaledger.common.security;

/** Claim names in AlphaLedger access tokens. The subject ({@code sub}) is the numeric user ID. */
public final class JwtClaims {

    public static final String EMAIL = "email";
    public static final String NAME = "name";
    public static final String ROLES = "roles";

    private JwtClaims() {
    }
}
