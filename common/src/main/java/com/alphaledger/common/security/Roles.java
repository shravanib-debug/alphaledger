package com.alphaledger.common.security;

/** Role codes carried in the JWT {@code roles} claim (prd.md §3, role permissions). */
public final class Roles {

    public static final String ADMIN = "ADMIN";
    public static final String PM = "PM";
    public static final String OPS_ANALYST = "OPS_ANALYST";
    public static final String AUDITOR = "AUDITOR";

    private Roles() {
    }
}
