package com.securebank.transaction.web.dto;

import com.securebank.transaction.domain.OperationStatus;
import com.securebank.transaction.domain.OperationType;
import com.securebank.transaction.domain.TargetService;
import com.securebank.transaction.domain.TransferOperation;
import java.time.Instant;
import java.util.UUID;

public record OperationResponse(UUID operationId, OperationType type, TargetService targetService,
                                OperationStatus status, int attemptCount, String resultCode, String lastError,
                                Instant createdAt, Instant updatedAt) {

    public static OperationResponse from(TransferOperation o) {
        return new OperationResponse(o.getOperationId(), o.getOperationType(), o.getTargetService(), o.getStatus(),
                o.getAttemptCount(), o.getResultCode(), o.getLastError(), o.getCreatedAt(), o.getUpdatedAt());
    }
}
