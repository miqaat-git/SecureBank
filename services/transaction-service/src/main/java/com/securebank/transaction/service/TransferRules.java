package com.securebank.transaction.service;

import com.securebank.transaction.config.TransferProperties;
import com.securebank.transaction.exception.ApiException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Pure business validation of a transfer request. Nothing is rounded: invalid input is rejected. */
@Component
public class TransferRules {

    static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{8,64}$");

    private final TransferProperties props;

    public TransferRules(TransferProperties props) {
        this.props = props;
    }

    /** @return the amount normalized to scale 2 (never rounded) */
    public BigDecimal validate(String idempotencyKey, TransferCommand cmd) {
        if (idempotencyKey == null || !KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 8-64 characters of letters, digits, '_', '-', '.', ':'.");
        }
        if (cmd.sourceAccountId() == null || cmd.destinationAccountId() == null || cmd.amount() == null
                || cmd.currency() == null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "All transfer fields are required.");
        }
        if (cmd.sourceAccountId().equals(cmd.destinationAccountId())) {
            throw ApiException.unprocessable("SAME_ACCOUNT", "Source and destination accounts must differ.");
        }
        String currency = cmd.currency().toUpperCase(Locale.ROOT);
        if (!props.supportedCurrencies().contains(currency)) {
            throw ApiException.unprocessable("UNSUPPORTED_CURRENCY", "Currency is not supported.");
        }
        BigDecimal amount = cmd.amount();
        if (amount.signum() <= 0) {
            throw ApiException.unprocessable("INVALID_AMOUNT", "Amount must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("INVALID_AMOUNT", "Amount must have at most 2 decimal places.");
        }
        BigDecimal normalized = amount.setScale(2);   // exact: scale was verified above
        if (normalized.compareTo(props.maxAmount()) > 0) {
            throw ApiException.unprocessable("AMOUNT_EXCEEDS_LIMIT", "Amount exceeds the maximum allowed.");
        }
        return normalized;
    }
}
