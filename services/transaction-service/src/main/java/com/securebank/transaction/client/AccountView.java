package com.securebank.transaction.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Expected Account Service lookup response (contract to align): GET {account-path}.
 * status must be "ACTIVE" for an account to take part in a transfer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountView(UUID accountId, String customerId, String status, String currency) {

    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
