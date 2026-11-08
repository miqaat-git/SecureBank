package com.securebank.account.domain;

import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Monetary rules for INR amounts.
 *
 * <ul>
 *   <li>Scale is exactly 2 (paise). Stored as DECIMAL(19,2).</li>
 *   <li>Explicit rounding rule: <b>no rounding is ever performed</b>. An amount with more than two significant
 *       decimal places is rejected instead of being silently rounded ({@link RoundingMode#UNNECESSARY}).</li>
 *   <li>The largest representable amount/balance is {@link #MAX_AMOUNT} (17 integer digits).</li>
 * </ul>
 */
public final class Money {

    public static final int SCALE = 2;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999.99");

    private static final int MAX_INTEGER_DIGITS = 17;

    private Money() {
    }

    /**
     * Validates a strictly positive monetary amount and returns it with scale 2.
     *
     * @throws ApiException INVALID_AMOUNT when null, not positive, too large or with more than 2 decimals
     */
    public static BigDecimal normalizePositive(BigDecimal amount) {
        if (amount == null) {
            throw invalid("Amount is required.");
        }
        if (amount.signum() <= 0) {
            throw invalid("Amount must be greater than zero.");
        }
        // Checked before any setScale call so that absurd exponents (e.g. 1E+400) cannot allocate huge numbers.
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw invalid("Amount is too large.");
        }
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw invalid("Amount must have at most 2 decimal places.");
        }
        return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_AMOUNT, message);
    }
}
