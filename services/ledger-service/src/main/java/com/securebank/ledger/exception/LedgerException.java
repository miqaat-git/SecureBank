package com.securebank.ledger.exception;

import org.springframework.http.HttpStatus;

/** Base type for all expected, client-facing failures. Carries its HTTP status and a stable error code. */
public abstract class LedgerException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected LedgerException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
