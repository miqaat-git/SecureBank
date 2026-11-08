package com.securebank.gateway;

import com.securebank.gateway.support.Downstreams;
import com.securebank.gateway.support.GatewayTest;
import com.securebank.gateway.support.TestJwtConfig;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The account service is pointed at a port nothing listens on. Deliberately does not extend
 * AbstractGatewayTest so its property registration cannot clash with the shared one.
 */
@GatewayTest
class UnavailableDownstreamTest {

    @Autowired
    WebTestClient client;

    @DynamicPropertySource
    static void closedAccountService(DynamicPropertyRegistry registry) throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Downstreams.register(registry);
        registry.add("securebank.services.account-base-url", () -> "http://127.0.0.1:" + closedPort);
    }

    @BeforeEach
    void reset() {
        Downstreams.reset();
    }

    @Test
    void unreachableServiceYieldsSanitizedServiceUnavailable() {
        client.get().uri("/api/accounts/1")
                .header("Authorization", TestJwtConfig.BEARER)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(String.class)
                .value(body -> ResilienceTest.assertSanitized(body, "SERVICE_UNAVAILABLE"));
    }

    @Test
    void unreachableServiceAlsoFailsSafelyForPost() {
        client.post().uri("/api/accounts")
                .header("Authorization", TestJwtConfig.BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"a\":1}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void otherServicesStillWork() {
        client.get().uri("/api/customers/1")
                .header("Authorization", TestJwtConfig.BEARER)
                .exchange()
                .expectStatus().isOk();
    }
}
