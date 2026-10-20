package com.securebank.customer.exception;

/** A well-formed request that breaks a business or parameter rule (HTTP 400). */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
