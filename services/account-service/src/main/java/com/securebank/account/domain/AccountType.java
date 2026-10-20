package com.securebank.account.domain;

/** Kind of account. Both types disallow negative balances (no overdraft policy exists in this service). */
public enum AccountType {
    SAVINGS,
    CURRENT
}
