package com.securebank.ledger.dto;

import com.securebank.ledger.config.LedgerConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request to compensate a posted entry. The business reference is the idempotency key of the reversal. */
public record ReverseEntryRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = LedgerConstants.BUSINESS_REFERENCE_PATTERN)
        String businessReference,
        @NotBlank @Size(max = 300)
        String reason) {
}
