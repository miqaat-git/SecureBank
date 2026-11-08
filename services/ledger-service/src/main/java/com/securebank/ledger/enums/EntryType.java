package com.securebank.ledger.enums;

public enum EntryType {
    /** An ordinary business entry. */
    NORMAL,
    /** A compensating entry that mirrors (swaps debit/credit of) exactly one earlier NORMAL entry. */
    REVERSAL
}
