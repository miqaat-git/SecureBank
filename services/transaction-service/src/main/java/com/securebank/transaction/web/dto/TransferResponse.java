package com.securebank.transaction.web.dto;

import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transferId,
        String idempotencyKey,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public static TransferResponse from(Transfer t) {
        return new TransferResponse(t.getId(), t.getIdempotencyKey(), t.getSourceAccountId(),
                t.getDestinationAccountId(), t.getAmount(), t.getCurrency(), t.getStatus(), t.getErrorCode(),
                t.getErrorMessage(), t.getCreatedAt(), t.getUpdatedAt(), t.getCompletedAt());
    }
}
