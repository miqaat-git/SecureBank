package com.securebank.account.dto;

import com.securebank.account.domain.AccountStatus;
import java.time.Instant;
import java.util.UUID;

public record AccountStatusResponse(UUID accountId, String accountNumber, AccountStatus status,
                                    Instant statusChangedAt) {
}
