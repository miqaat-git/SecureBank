package com.securebank.account.dto;

import com.securebank.account.domain.BalanceOperationType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Result of an internal balance operation, for the Transaction Service. {@code status} is always
 * {@code "SUCCEEDED"}: a failed operation is reported with an error status code and body instead, never with a
 * 2xx. The Transaction Service treats a 2xx without a recognised {@code status} as UNKNOWN.
 *
 * @param replayed true when the operation had already been applied (idempotent replay or status lookup)
 */
public record InternalOperationResponse(
        String status,
        UUID operationId,
        UUID accountId,
        BalanceOperationType type,
        BigDecimal amount,
        BigDecimal resultingBalance,
        UUID transferId,
        boolean replayed,
        Instant createdAt) {

    public static final String SUCCEEDED = "SUCCEEDED";
}
