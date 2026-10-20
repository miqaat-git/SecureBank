package com.securebank.transaction.client;

import java.util.Optional;
import java.util.UUID;

/**
 * Client contract for Account Service. See README "Required Account Service contract".
 * Implementations must never throw for financial operations: every outcome is classified in a RemoteResult.
 */
public interface AccountServiceClient {

    /** Read-only lookup. Empty if the account does not exist. Throws RemoteCallException if unreachable. */
    Optional<AccountView> findAccount(UUID accountId);

    /** Idempotent by operationId (also sent as the Idempotency-Key header). */
    RemoteResult applyOperation(AccountOperationRequest request);

    /** Status lookup of a previously submitted operation. NOT_FOUND means the service never recorded it. */
    RemoteResult getOperation(UUID accountId, UUID operationId);
}
