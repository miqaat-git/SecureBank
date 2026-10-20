package com.securebank.transaction.client;

public enum AccountOperationType {
    /** Atomically check available funds and debit the account (owned by Account Service). */
    DEBIT,
    /** Credit the account. */
    CREDIT,
    /** Compensation: return a previously applied DEBIT to the source account. */
    REVERSE_DEBIT
}
