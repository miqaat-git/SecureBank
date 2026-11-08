package com.securebank.ledger.dto;

import com.securebank.ledger.config.LedgerConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Posting request exactly as Transaction Service sends it ({@code POST /internal/journal-entries}).
 * {@code idempotencyKey} is the Transaction Service operation id. It is accepted for contract compatibility;
 * replay detection is done by the Ledger on the normalised business reference plus the request fingerprint.
 */
public record InternalPostRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = LedgerConstants.INTERNAL_BUSINESS_REFERENCE_PATTERN)
        String businessReference,
        @NotNull UUID idempotencyKey,
        @NotBlank @Size(max = 500) String description,
        @NotBlank @Pattern(regexp = LedgerConstants.INTERNAL_CURRENCY_PATTERN) String currency,
        @NotNull @Size(max = 1000) List<@Valid @NotNull InternalEntryLine> entries) {
}
