package com.securebank.ledger.repository;

import java.math.BigDecimal;

/** Aggregate of debit and credit lines. Sums are null when no line matched. */
public record AccountTotals(BigDecimal totalDebits, BigDecimal totalCredits, Long lineCount) {
}
