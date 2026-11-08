package com.securebank.transaction.domain;

/**
 * PENDING  - persisted, not yet answered by the remote service (or only provably-not-applied failures so far).
 * SUCCEEDED/REJECTED - definitive remote outcome.
 * UNKNOWN  - an ambiguous outcome occurred (timeout etc.); the remote side may have applied the operation.
 *            UNKNOWN is sticky: only a definitive remote answer clears it.
 */
public enum OperationStatus {
    PENDING,
    SUCCEEDED,
    REJECTED,
    UNKNOWN;

    public boolean isFinal() {
        return this == SUCCEEDED || this == REJECTED;
    }
}
