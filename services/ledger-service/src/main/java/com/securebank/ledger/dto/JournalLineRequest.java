package com.securebank.ledger.dto;

import com.securebank.ledger.config.LedgerConstants;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * One line of a journal entry. Exactly one of debitAmount / creditAmount must be positive; the other
 * must be zero or omitted. {@code currency} is optional and, when present, must equal the entry currency.
 */
public record JournalLineRequest(
        @NotNull @Min(1) @Max(100000)
        Integer lineNumber,
        @NotBlank @Pattern(regexp = LedgerConstants.ACCOUNT_REFERENCE_PATTERN)
        String accountReference,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        @Pattern(regexp = LedgerConstants.CURRENCY_PATTERN)
        String currency,
        @Size(max = 255)
        String description) {
}
