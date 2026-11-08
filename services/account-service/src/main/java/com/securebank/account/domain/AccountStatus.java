package com.securebank.account.domain;

/**
 * Lifecycle of an account (Blueprint D6/R1/R2).
 * <pre>
 *   (application) -> PENDING   (customer applies; zero balance; cannot send, receive or be funded)
 *   PENDING -> ACTIVE          (ADMIN approves, reason + audit record)
 *   PENDING -> REJECTED        (ADMIN rejects, reason + audit record; terminal for that application)
 *   ACTIVE <-> FROZEN          (administrative freeze / unfreeze)
 *   ACTIVE  -> CLOSED          (terminal; only with zero balance)
 * </pre>
 * Only ACTIVE accounts accept balance operations. After a rejection the customer may submit a new application.
 */
public enum AccountStatus {
    PENDING,
    REJECTED,
    ACTIVE,
    FROZEN,
    CLOSED
}
