package com.securebank.ledger.dto;

import com.securebank.ledger.enums.EntryStatus;
import com.securebank.ledger.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Entry header without lines, used for listings. */
public record JournalEntrySummaryResponse(
        UUID id,
        String businessReference,
        String currency,
        String description,
        EntryStatus status,
        EntryType entryType,
        BigDecimal totalAmount,
        UUID reversesEntryId,
        Instant createdAt) {
}
