package com.securebank.transaction.security;

/**
 * Authenticated caller derived ONLY from the validated JWT (never from request bodies).
 *
 * @param subject    JWT subject
 * @param customerId SecureBank customer id taken from a configurable claim; null if the token has none
 * @param operator   true if the caller holds the transfer_operator role
 */
public record Caller(String subject, String customerId, boolean operator) {
}
