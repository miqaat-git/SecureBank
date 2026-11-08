package com.securebank.gateway;

import com.securebank.gateway.support.AbstractGatewayTest;
import com.securebank.gateway.support.GatewayTest;
import com.securebank.gateway.support.TestJwtConfig;
import org.junit.jupiter.api.Test;

/** CORS behaviour for the browser calls the frontend needs (preflight carries no token). */
@GatewayTest
class CorsApiContractTest extends AbstractGatewayTest {

    private static final String ALLOWED = "https://app.securebank.test";

    @Test
    void transferPreflightAllowsAuthorizationAndIdempotencyKeyHeadersFromTheConfiguredOrigin() {
        client.options().uri("/api/transactions")
                .header("Origin", ALLOWED)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", ALLOWED)
                .expectHeader().exists("Access-Control-Allow-Headers")
                .expectHeader().doesNotExist("Access-Control-Allow-Credentials");
    }

    @Test
    void preflightRequestingAnUndocumentedHeaderIsRefused() {
        client.options().uri("/api/transactions")
                .header("Origin", ALLOWED)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-user-role")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @Test
    void preflightFromAnOtherOriginIsRefusedOnEveryPublicPrefix() {
        for (String path : new String[]{"/api/customers/1", "/api/accounts/1", "/api/account-numbers/1",
                "/api/admin/accounts", "/api/transactions", "/api/ledger/x"}) {
            client.options().uri(path)
                    .header("Origin", "https://evil.example")
                    .header("Access-Control-Request-Method", "GET")
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        }
    }

    @Test
    void localhostOriginsAreNotAllowedUnlessExplicitlyConfigured() {
        // The test profile only allows https://app.securebank.test; the default configuration allows no origin.
        for (String origin : new String[]{"http://localhost:5173", "http://localhost:3000"}) {
            client.options().uri("/api/transactions")
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "GET")
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        }
    }

    @Test
    void internalPathsNeverGetCorsHeaders() {
        client.options().uri("/internal/accounts/1/operations")
                .header("Origin", ALLOWED)
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        client.post().uri("/internal/journal-entries")
                .header("Origin", ALLOWED)
                .header("Authorization", TestJwtConfig.BEARER)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }
}
