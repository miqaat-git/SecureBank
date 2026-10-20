package com.securebank.ledger.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountLedgerLineResponse(
        UUID lineId,
        UUID entryId,
        String businessReference,
        int lineNumber,
        String accountReference,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        String currency,
        String description,
        Instant postedAt) {
}
