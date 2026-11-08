package com.securebank.account.dto;

import com.securebank.account.domain.AccountStatus;
import java.time.Instant;

/** One status change. actorSubject is only filled for administrators; customers see the role only. */
public record AccountStatusHistoryResponse(AccountStatus previousStatus, AccountStatus newStatus, String reason,
                                           String actorRole, String actorSubject, Instant occurredAt) {
}
