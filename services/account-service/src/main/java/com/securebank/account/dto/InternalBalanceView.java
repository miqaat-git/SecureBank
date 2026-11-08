package com.securebank.account.dto;

import com.securebank.account.domain.AccountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Reconciliation view of one account: identifiers and balance only, no customer data. */
public record InternalBalanceView(UUID accountId, String currency, AccountStatus status, BigDecimal availableBalance,
                                  Long version, Instant asOf) {
}
