package com.securebank.transaction.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One row of Account Service's read-only balance listing. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountBalanceRow(UUID accountId, String currency, String status, BigDecimal availableBalance,
                                Long version, Instant asOf) {
}
