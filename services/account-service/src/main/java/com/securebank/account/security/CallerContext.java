package com.securebank.account.security;

import java.util.UUID;

/**
 * The authenticated caller as seen by the service layer.
 *
 * @param subject    JWT subject (user or service account id), used for audit fields
 * @param admin      true for ROLE_ADMIN
 * @param customerId customer UUID from the token (CUSTOMER role only), otherwise null
 */
public record CallerContext(String subject, boolean admin, UUID customerId) {

    /** Admins may access every customer; a customer only their own. */
    public boolean canAccessCustomer(UUID targetCustomerId) {
        return admin || (customerId != null && customerId.equals(targetCustomerId));
    }
}
