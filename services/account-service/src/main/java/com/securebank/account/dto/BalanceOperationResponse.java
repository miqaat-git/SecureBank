package com.securebank.account.dto;

import com.securebank.account.domain.BalanceOperationType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Result of an internal balance operation.
 *
 * @param replayed true when the idempotency key had already been applied and the stored result is returned
 */
public record BalanceOperationResponse(
        UUID operationId,
        UUID accountId,
        BalanceOperationType operationType,
        BigDecimal amount,
        BigDecimal resultingBalance,
        UUID referenceId,
        boolean replayed,
        Instant createdAt) {
}
