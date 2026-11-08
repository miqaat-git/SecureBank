package com.securebank.account.client;

import java.util.Optional;
import java.util.UUID;

/**
 * Port to the Customer Service. Implementations must never assume that a customer exists when the remote service
 * cannot be reached.
 */
public interface CustomerClient {

    /**
     * @return the customer, or empty when Customer Service answers that the customer does not exist
     * @throws com.securebank.account.exception.ApiException CUSTOMER_SERVICE_UNAVAILABLE on any timeout,
     *                                                       connection error, unexpected status or bad payload
     */
    Optional<CustomerSummary> findCustomer(UUID customerId);
}
