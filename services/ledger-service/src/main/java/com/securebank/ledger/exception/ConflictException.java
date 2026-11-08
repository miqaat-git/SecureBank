package com.securebank.ledger.exception;

import org.springframework.http.HttpStatus;

/** Idempotency or state conflict (HTTP 409). */
public class ConflictException extends LedgerException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
