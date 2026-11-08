package com.securebank.transaction.client;

import java.util.UUID;

/** Read-only access to Account Service balances (reconciliation view). Never changes an account. */
public interface AccountBalanceReader {

    /** Throws {@link RemoteCallException} when Account Service cannot be consulted or answers unexpectedly. */
    AccountBalancePage list(String status, String currency, UUID accountId, int page, int size);
}
