package com.securebank.transaction.client;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.util.UUID;

/** Body of POST {operations-path}. accountId travels in the path, not in the JSON body. */
public record AccountOperationRequest(
        @JsonIgnore UUID accountId,
        UUID operationId,
        AccountOperationType type,
        BigDecimal amount,
        String currency,
        UUID transferId,
        UUID reversesOperationId) {
}
