package com.securebank.account.dto;

import com.securebank.account.domain.AccountStatus;
import java.util.UUID;

/** Minimal account view for the Transaction Service ({@code GET /internal/accounts/{accountId}}). No balance. */
public record InternalAccountView(UUID accountId, UUID customerId, AccountStatus status, String currency) {
}
