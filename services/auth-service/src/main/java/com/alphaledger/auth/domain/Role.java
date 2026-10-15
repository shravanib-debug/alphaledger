package com.alphaledger.auth.domain;

/** User roles; codes match the {@code role} table and the JWT {@code roles} claim. */
public enum Role {
    ADMIN,
    PM,
    OPS_ANALYST,
    AUDITOR
}
