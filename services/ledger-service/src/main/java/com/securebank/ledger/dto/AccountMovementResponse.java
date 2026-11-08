package com.securebank.ledger.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Accounting view of an account's posted ledger lines in [from, to).
 * netMovement = totalDebits - totalCredits (debit-positive convention). This is NOT the account's
 * operational/available balance, which is owned by Account Service.
 */
public record AccountMovementResponse(
        String accountReference,
        String currency,
        Instant from,
        Instant to,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        BigDecimal netMovement,
        long lineCount,
        String convention) {
}
