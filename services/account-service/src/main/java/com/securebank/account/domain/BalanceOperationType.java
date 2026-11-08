package com.securebank.account.domain;

/** Direction of an internal balance adjustment requested by the Transaction Service. */
public enum BalanceOperationType {
    DEBIT,
    CREDIT,
    /** Compensation: returns a previously applied DEBIT (referenced by reversesKey) to the same account. */
    REVERSE_DEBIT
}
