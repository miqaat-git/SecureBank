package com.securebank.transaction.client;

import java.util.Collection;
import java.util.UUID;

/** Read-only access to Ledger-derived totals (reconciliation view). Never posts or reverses anything. */
public interface LedgerBalanceReader {

    /** Throws {@link RemoteCallException} when Ledger Service cannot be consulted or answers unexpectedly. */
    LedgerBalances balances(Collection<UUID> accountIds);
}
