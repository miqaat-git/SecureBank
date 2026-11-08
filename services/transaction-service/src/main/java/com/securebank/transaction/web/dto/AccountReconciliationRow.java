package com.securebank.transaction.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One account. difference = accountBalance - ledgerBalance (ledger balance = credits - debits of posted lines in the
 * account's currency, i.e. the customer-liability view). reasonCode is null for MATCH.
 */
public record AccountReconciliationRow(UUID accountId, String currency, String accountStatus,
                                       BigDecimal accountBalance, BigDecimal ledgerBalance, BigDecimal difference,
                                       ReconciliationResult result, String reasonCode, long ledgerLineCount,
                                       Instant accountReadAt) {
}
