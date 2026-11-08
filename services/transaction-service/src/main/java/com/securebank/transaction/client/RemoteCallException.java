package com.securebank.transaction.client;

/** Thrown by read-only lookups when the remote service cannot be consulted (no financial side effect). */
public class RemoteCallException extends RuntimeException {

    private final String code;

    public RemoteCallException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
