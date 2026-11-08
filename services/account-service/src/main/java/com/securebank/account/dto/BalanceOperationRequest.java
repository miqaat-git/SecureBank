package com.securebank.account.dto;

import com.securebank.account.domain.BalanceOperationType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Internal balance adjustment sent by the Transaction Service (service-to-service only).
 *
 * @param idempotencyKey unique per logical operation; retries with the same key are applied once
 * @param referenceId    the Transaction Service's transaction identifier, kept for reconciliation
 */
public record BalanceOperationRequest(
        @NotNull(message = "operationType is required (DEBIT or CREDIT)") BalanceOperationType operationType,
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        @Digits(integer = 17, fraction = 2, message = "amount must have at most 17 integer and 2 decimal digits")
        BigDecimal amount,
        @NotBlank(message = "idempotencyKey is required")
        @Size(min = 8, max = 64, message = "idempotencyKey must be 8 to 64 characters")
        @Pattern(regexp = "^[A-Za-z0-9._:-]+$", message = "idempotencyKey may only contain letters, digits, . _ : -")
        String idempotencyKey,
        @NotNull(message = "referenceId is required") UUID referenceId) {
}
