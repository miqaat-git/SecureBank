package com.securebank.account.dto;

import com.securebank.account.domain.BalanceOperationType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Canonical Transaction Service -> Account Service balance operation
 * ({@code POST /internal/accounts/{accountId}/operations}). Field names match the Transaction Service's
 * {@code AccountOperationRequest} exactly; {@code accountId} travels in the path.
 *
 * @param operationId         stable id of this operation; also the idempotency key (retries reuse it)
 * @param type                DEBIT, CREDIT or REVERSE_DEBIT
 * @param transferId          the transfer this operation belongs to (kept for reconciliation)
 * @param reversesOperationId required for REVERSE_DEBIT (the debit to undo), forbidden otherwise
 */
public record InternalOperationRequest(
        @NotNull(message = "operationId is required") UUID operationId,
        @NotNull(message = "type is required (DEBIT, CREDIT or REVERSE_DEBIT)") BalanceOperationType type,
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        @Digits(integer = 17, fraction = 2, message = "amount must have at most 17 integer and 2 decimal digits")
        BigDecimal amount,
        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter upper-case code")
        String currency,
        @NotNull(message = "transferId is required") UUID transferId,
        UUID reversesOperationId) {
}
