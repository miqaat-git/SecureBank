package com.securebank.ledger.dto;

import com.securebank.ledger.enums.EntryStatus;
import com.securebank.ledger.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Full journal entry including its lines. {@code reversedByEntryId} is derived, not stored on the original. */
public record JournalEntryResponse(
        UUID id,
        String businessReference,
        String currency,
        String description,
        EntryStatus status,
        EntryType entryType,
        BigDecimal totalAmount,
        UUID reversesEntryId,
        UUID reversedByEntryId,
        String createdBy,
        Instant createdAt,
        List<JournalLineResponse> lines) {
}
