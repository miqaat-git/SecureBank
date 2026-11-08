package com.securebank.ledger.dto;

import com.securebank.ledger.config.LedgerConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Reversal body as Transaction Service sends it. The ORIGINAL business reference is in the URL path
 * ({@code POST /internal/journal-entries/{businessReference}/reversals}), not in this body.
 */
public record InternalReversalRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = LedgerConstants.INTERNAL_BUSINESS_REFERENCE_PATTERN)
        String reversalBusinessReference,
        @NotNull UUID idempotencyKey,
        @NotBlank @Size(max = 300) String reason) {
}
