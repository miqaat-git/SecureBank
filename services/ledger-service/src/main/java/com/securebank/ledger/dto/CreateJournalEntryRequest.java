package com.securebank.ledger.dto;

import com.securebank.ledger.config.LedgerConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request to create and post a balanced journal entry.
 * Structural checks happen here (HTTP 400); accounting rules are enforced by JournalEntryValidator (HTTP 422).
 */
public record CreateJournalEntryRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = LedgerConstants.BUSINESS_REFERENCE_PATTERN)
        String businessReference,
        @NotBlank @Pattern(regexp = LedgerConstants.CURRENCY_PATTERN)
        String currency,
        @NotBlank @Size(max = 500)
        String description,
        @NotNull @Size(max = 1000)
        List<@Valid @NotNull JournalLineRequest> lines) {
}
