package com.securebank.transaction.security;

/**
 * Authenticated caller derived ONLY from the validated JWT (never from request bodies).
 *
 * @param subject    JWT subject
 * @param customerId SecureBank customer id taken from a configurable claim; null if the token has none
 * @param admin      true if the caller holds the human ADMIN role (Blueprint D2: roles are CUSTOMER and ADMIN)
 */
public record Caller(String subject, String customerId, boolean admin) {
}
