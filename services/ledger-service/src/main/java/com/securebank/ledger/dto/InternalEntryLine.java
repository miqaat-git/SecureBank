package com.securebank.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line as sent by Transaction Service. Amount sign, precision and balance are validated by the Ledger's own
 * {@code JournalEntryValidator} after translation; this record only checks the shape.
 */
public record InternalEntryLine(
        @NotNull UUID accountId,
        @NotBlank @Pattern(regexp = "^(DEBIT|CREDIT)$", message = "direction must be DEBIT or CREDIT")
        String direction,
        @NotNull BigDecimal amount) {
}
