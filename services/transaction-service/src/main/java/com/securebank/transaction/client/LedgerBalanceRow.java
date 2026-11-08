package com.securebank.transaction.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/** Posted-line totals for one account in one currency; creditMinusDebit is the customer-balance (liability) view. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerBalanceRow(String accountId, String currency, BigDecimal totalDebits, BigDecimal totalCredits,
                               BigDecimal creditMinusDebit, long lineCount) {
}
