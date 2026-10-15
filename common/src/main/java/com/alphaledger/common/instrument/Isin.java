package com.alphaledger.common.instrument;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ISIN rules (ISO 6166): 2-letter country prefix, 9 alphanumeric characters, 1 check digit computed with
 * the Luhn algorithm over the letters-as-numbers expansion. AlphaLedger's synthetic private instruments
 * use the reserved {@value #SYNTHETIC_PREFIX} prefix and are not real ISINs (prd.md §10).
 */
public final class Isin {

    public static final String SYNTHETIC_PREFIX = "ZZSYN";
    private static final Pattern FORMAT = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

    private Isin() {
    }

    public static String normalize(String isin) {
        return isin == null ? null : isin.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean hasValidFormat(String isin) {
        return isin != null && FORMAT.matcher(isin).matches();
    }

    public static boolean isSynthetic(String isin) {
        return isin != null && isin.startsWith(SYNTHETIC_PREFIX);
    }

    /** True for a well-formed ISIN whose check digit is correct. */
    public static boolean isValid(String isin) {
        return hasValidFormat(isin) && checkDigit(isin.substring(0, 11)) == isin.charAt(11) - '0';
    }

    /** Luhn check digit over the first 11 characters, with A=10 ... Z=35. */
    static int checkDigit(String first11) {
        StringBuilder digits = new StringBuilder();
        for (char c : first11.toCharArray()) {
            digits.append(Character.digit(c, 36));
        }
        int sum = 0;
        boolean doubleIt = true; // the check digit will sit to the right, so the rightmost digit is doubled
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return (10 - sum % 10) % 10;
    }
}
