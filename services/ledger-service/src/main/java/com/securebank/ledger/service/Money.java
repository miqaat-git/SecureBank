package com.securebank.ledger.service;

import com.securebank.ledger.config.LedgerConstants;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact decimal helpers. No floating point and no silent rounding anywhere in the ledger. */
public final class Money {

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(LedgerConstants.STORAGE_SCALE);

    private Money() {
    }

    /**
     * Normalises to the storage scale (4). Throws ArithmeticException if non-zero digits would be lost,
     * which cannot happen for amounts that passed {@link JournalEntryValidator}.
     */
    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(LedgerConstants.STORAGE_SCALE, RoundingMode.UNNECESSARY);
    }
}
