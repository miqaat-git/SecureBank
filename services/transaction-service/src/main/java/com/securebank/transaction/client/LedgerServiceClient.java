package com.securebank.transaction.client;

/**
 * Client contract for Ledger Service. See README "Required Ledger Service contract".
 * Implementations must never throw for financial operations: every outcome is classified in a RemoteResult.
 */
public interface LedgerServiceClient {

    /** Posts a balanced journal entry, idempotent by businessReference. */
    RemoteResult post(LedgerPostRequest request);

    /** Posts a reversing journal entry (never edits the original). */
    RemoteResult reverse(LedgerReversalRequest request);

    /** Looks up a journal entry by business reference. SUCCEEDED means it exists/is posted. */
    RemoteResult getPosting(String businessReference);
}
