package com.securebank.transaction.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerBalances(Instant generatedAt, List<LedgerBalanceRow> balances) {
}
