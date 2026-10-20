package com.securebank.transaction.client;

import java.util.Optional;

/**
 * Strategy for service-to-service authentication. The default implementation uses a static bearer token
 * from the environment; an OAuth2 client-credentials implementation can replace it without touching clients.
 */
public interface ServiceTokenProvider {

    Optional<String> bearerToken();
}
