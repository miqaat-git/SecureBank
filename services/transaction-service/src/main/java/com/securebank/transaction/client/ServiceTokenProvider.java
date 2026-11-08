package com.securebank.transaction.client;

import java.util.Optional;

/**
 * Strategy for service-to-service authentication. The default implementation uses a static bearer token
 * from the environment; an OAuth2 client-credentials implementation can replace it without touching clients.
 */
public interface ServiceTokenProvider {

    /**
     * @throws ServiceTokenException when a token is required but cannot be obtained; the caller must treat the
     *                               request as not sent
     */
    Optional<String> bearerToken();
}
