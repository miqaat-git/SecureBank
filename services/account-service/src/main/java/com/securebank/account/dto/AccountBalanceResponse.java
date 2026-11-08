package com.securebank.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Account-level balance state. The Ledger Service remains the source of truth for accounting entries. */
public record AccountBalanceResponse(UUID accountId, String currency, BigDecimal availableBalance, Instant asOf,
                                     Long version) {
}
