package com.securebank.ledger.repository;

import java.math.BigDecimal;

/** Debit and credit sums of the posted lines of one account reference in one currency. */
public record AccountCurrencyTotals(String accountReference, String currency, BigDecimal totalDebits,
                                    BigDecimal totalCredits, Long lineCount) {
}
