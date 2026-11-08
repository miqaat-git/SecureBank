package com.securebank.gateway.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

public abstract class AbstractGatewayTest {

    /**
     * Requests must carry an absolute URI. Without a base URL, WebTestClient sends the mock
     * exchange a relative URI (no scheme/host), and Spring's CorsUtils.isCorsRequest() throws
     * "Actual request scheme must not be null" for any request that has an Origin header;
     * DefaultCorsProcessor reports that as "origin is malformed" and answers 403.
     * A real HTTP client always sends an absolute request line/Host header, so this only
     * makes the test request realistic. It does not relax any CORS or security rule.
     */
    protected WebTestClient client;

    @Autowired
    void configureClient(WebTestClient injected) {
        this.client = injected.mutate().baseUrl("http://localhost").build();
    }

    @DynamicPropertySource
    public static void downstreams(DynamicPropertyRegistry registry) {
        Downstreams.register(registry);
    }

    @BeforeEach
    protected void resetDownstreams() {
        Downstreams.reset();
    }
}
