package com.securebank.transaction.client;

import java.util.List;
import java.util.UUID;

/** Body of POST {post-path}. businessReference is unique per journal entry; idempotencyKey = operation id. */
public record LedgerPostRequest(
        String businessReference,
        UUID idempotencyKey,
        String description,
        String currency,
        List<LedgerEntryLine> entries) {
}
