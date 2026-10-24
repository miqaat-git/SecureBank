package com.securebank.transaction.client;

/**
 * A service-to-service access token could not be obtained. The message is a fixed, safe code: it never carries
 * the identity provider's response, the client secret or any token, and the cause is deliberately not chained.
 */
public class ServiceTokenException extends RuntimeException {

    public ServiceTokenException(String code) {
        super(code, null, false, false);
    }
}
