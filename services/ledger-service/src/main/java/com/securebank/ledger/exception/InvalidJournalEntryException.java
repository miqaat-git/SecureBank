package com.securebank.ledger.exception;

import org.springframework.http.HttpStatus;

/** An accounting rule was violated (HTTP 422). */
public class InvalidJournalEntryException extends LedgerException {

    public InvalidJournalEntryException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
