package com.securebank.account.domain;

/**
 * Lifecycle of an account.
 * <pre>
 *   ACTIVE <-> FROZEN        (administrative freeze / unfreeze)
 *   ACTIVE  -> CLOSED        (terminal; only with zero balance)
 * </pre>
 * Only ACTIVE accounts accept balance operations.
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
