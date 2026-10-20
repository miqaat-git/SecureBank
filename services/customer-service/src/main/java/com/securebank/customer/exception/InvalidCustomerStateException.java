package com.securebank.customer.exception;

/** The customer's current status does not allow the operation (HTTP 409). */
public class InvalidCustomerStateException extends RuntimeException {

    public InvalidCustomerStateException(String message) {
        super(message);
    }
}
