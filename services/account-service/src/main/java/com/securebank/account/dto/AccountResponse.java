package com.securebank.account.dto;

import com.securebank.account.domain.AccountStatus;
import com.securebank.account.domain.AccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Public representation of an account. Contains no customer profile data. */
public record AccountResponse(
        UUID id,
        UUID customerId,
        String accountNumber,
        AccountType accountType,
        AccountStatus status,
        String currency,
        BigDecimal availableBalance,
        Instant createdAt,
        Instant updatedAt,
        Long version) {
}
