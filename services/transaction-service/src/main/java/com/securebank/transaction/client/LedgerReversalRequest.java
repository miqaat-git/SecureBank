package com.securebank.transaction.client;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/** Body of POST {reversal-path}. Posted entries are never edited; a reversal is a NEW entry. */
public record LedgerReversalRequest(
        @JsonIgnore String originalBusinessReference,
        String reversalBusinessReference,
        UUID idempotencyKey,
        String reason) {
}
