package com.securebank.ledger.dto;

import java.time.Instant;
import java.util.List;

/** Read-only answer for the reconciliation view. Accounts with no posted lines have no entry in {@code balances}. */
public record LedgerBalancesResponse(Instant generatedAt, List<LedgerBalanceLine> balances) {
}
