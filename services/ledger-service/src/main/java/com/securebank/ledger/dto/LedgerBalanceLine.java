package com.securebank.ledger.dto;

import java.math.BigDecimal;

/**
 * Posted-line totals of one account in one currency. creditMinusDebit is the credit-normal (liability) view used for
 * customer accounts: credits increase the customer's balance, debits decrease it.
 */
public record LedgerBalanceLine(String accountId, String currency, BigDecimal totalDebits, BigDecimal totalCredits,
                                BigDecimal creditMinusDebit, long lineCount) {
}
