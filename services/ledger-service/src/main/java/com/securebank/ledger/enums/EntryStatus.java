package com.securebank.ledger.enums;

/**
 * Posting status of a journal entry. Entries are posted atomically on creation, so only POSTED exists.
 * Posted entries are immutable: a correction is a new REVERSAL entry, never a status change.
 */
public enum EntryStatus {
    POSTED
}
