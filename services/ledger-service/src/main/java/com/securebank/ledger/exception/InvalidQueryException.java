package com.securebank.ledger.exception;

import org.springframework.http.HttpStatus;

/** A query/path parameter is invalid (HTTP 400). */
public class InvalidQueryException extends LedgerException {

    public InvalidQueryException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_QUERY", message);
    }
}
