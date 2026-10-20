package com.securebank.ledger.config;

/** Shared constants. Kept compile-time constant so they can be used in validation annotations. */
public final class LedgerConstants {

    /**
     * Account references are opaque identifiers owned by Account Service. Upper-case ASCII only, so
     * behaviour is identical under MySQL's case-insensitive default collation and in H2.
     */
    public static final String ACCOUNT_REFERENCE_PATTERN = "^[A-Z0-9][A-Z0-9._:-]{2,63}$";

    /** Business references are supplied by the caller (e.g. Transaction Service). Upper-case ASCII only. */
    public static final String BUSINESS_REFERENCE_PATTERN = "^[A-Z0-9][A-Z0-9._:-]{2,99}$";

    public static final String CURRENCY_PATTERN = "^[A-Z]{3}$";

    public static final String DEFAULT_CURRENCY = "INR";

    /** Matches DECIMAL(19,4) in the schema. */
    public static final int STORAGE_SCALE = 4;

    public static final int MAX_PAGE_SIZE = 200;

    private LedgerConstants() {
    }
}
