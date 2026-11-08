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

    static final Pattern REFERENCE_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{8,64}$");

    /** Validates an administrator funding request. @return the amount normalized to scale 2 (never rounded) */
    public BigDecimal validateFunding(String idempotencyKey, FundingCommand cmd) {
        if (idempotencyKey == null || !KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 8-64 characters of letters, digits, '_', '-', '.', ':'.");
        }
        if (cmd.targetAccountId() == null || cmd.amount() == null || cmd.currency() == null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "Target account, amount and currency are required.");
        }
        if (cmd.fundingReference() == null || !REFERENCE_PATTERN.matcher(cmd.fundingReference()).matches()) {
            throw ApiException.badRequest("INVALID_FUNDING_REFERENCE",
                    "fundingReference must be 8-64 characters of letters, digits, '_', '-', '.', ':'.");
        }
        if (cmd.reason() == null || cmd.reason().isBlank()) {
            throw ApiException.badRequest("REASON_REQUIRED", "A reason is required for funding.");
        }
        if (cmd.reason().length() > 255) {
            throw ApiException.badRequest("REASON_TOO_LONG", "The reason must be at most 255 characters.");
        }
        String currency = cmd.currency().toUpperCase(Locale.ROOT);
        if (!props.supportedCurrencies().contains(currency)) {
            throw ApiException.unprocessable("UNSUPPORTED_CURRENCY", "Currency is not supported.");
        }
        if (cmd.amount().signum() <= 0) {
            throw ApiException.unprocessable("INVALID_AMOUNT", "Amount must be greater than zero.");
        }
        if (cmd.amount().stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("INVALID_AMOUNT", "Amount must have at most 2 decimal places.");
        }
        BigDecimal normalized = cmd.amount().setScale(2);
        if (normalized.compareTo(props.funding().maxAmount()) > 0) {
            throw ApiException.unprocessable("AMOUNT_EXCEEDS_LIMIT",
                    "Amount exceeds the maximum allowed for one funding event.");
        }
        return normalized;
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
