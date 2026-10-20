package com.securebank.ledger.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends LedgerException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "ENTRY_NOT_FOUND", message);
    }
}
