package com.securebank.customer.exception;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException() {
        super("A customer with this email address already exists.");
    }
}
