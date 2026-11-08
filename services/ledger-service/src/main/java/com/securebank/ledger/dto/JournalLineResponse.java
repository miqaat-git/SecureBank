package com.securebank.ledger.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record JournalLineResponse(
        UUID id,
        int lineNumber,
        String accountReference,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        String currency,
        String description) {
}
