package com.securebank.gateway;

import com.securebank.gateway.support.AbstractGatewayTest;
import com.securebank.gateway.support.GatewayTest;
import com.securebank.gateway.support.TestJwtConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsUtils;

/** The test profile allows exactly one origin: https://app.securebank.test. */
@GatewayTest
class CorsTest extends AbstractGatewayTest {

    private static final String ALLOWED = "https://app.securebank.test";

    @Test
    void preflightFromAllowedOriginSucceedsWithoutToken() {
        client.options().uri("/api/accounts/1")
                .header("Origin", ALLOWED)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", ALLOWED)
                .expectHeader().exists("Access-Control-Allow-Methods");
    }

    @Test
    void preflightFromUnknownOriginIsRejected() {
        client.options().uri("/api/accounts/1")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @Test
    void actualRequestFromAllowedOriginGetsCorsHeaders() {
        client.get().uri("/api/customers/1")
                .header("Authorization", TestJwtConfig.BEARER)
                .header("Origin", ALLOWED)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", ALLOWED)
                .expectHeader().valueEquals("Access-Control-Expose-Headers", "X-Correlation-Id")
                .expectHeader().doesNotExist("Access-Control-Allow-Credentials");
    }

    @Test
    void requestFromUnknownOriginGetsNoCorsHeaders() {
        client.get().uri("/api/customers/1")
                .header("Authorization", TestJwtConfig.BEARER)
                .header("Origin", "https://evil.example")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }
    @Autowired
private CorsConfigurationSource corsSource;

@Test
void debugCorsConfiguration() {
    var request = MockServerHttpRequest
            .get("/api/customers/1")
            .header("Origin", ALLOWED)
            .build();

    var exchange = MockServerWebExchange.from(request);
    var config = corsSource.getCorsConfiguration(exchange);

    System.out.println("CORS config: " + config);

    if (config != null) {
        System.out.println("Allowed origins: " + config.getAllowedOrigins());
        System.out.println("Origin check: " + config.checkOrigin(ALLOWED));
        System.out.println("Allowed methods: " + config.getAllowedMethods());
        System.out.println("Allowed headers: " + config.getAllowedHeaders());
    } else {
        System.out.println("CORS configuration is NULL");
    }
}
@Test
void inspectOriginHeader() {
    var request = MockServerHttpRequest
        .options("http://localhost/api/accounts/1")
        .header("Origin", ALLOWED)
        .header("Access-Control-Request-Method", "GET")
        .build();
        
    var exchange = MockServerWebExchange.from(request);
    System.out.println("Request URI: " + exchange.getRequest().getURI());
System.out.println("Origin from headers: " +
        exchange.getRequest().getHeaders().getOrigin());

try {
    System.out.println("Is CORS request: " +
            CorsUtils.isCorsRequest(exchange.getRequest()));
} catch (Exception ex) {
    System.out.println("CORS exception: " + ex);
    ex.printStackTrace();
}

    var originHeaders = exchange.getRequest()
            .getHeaders()
            .getOrEmpty("Origin");

    System.out.println("Origin header values: " + originHeaders);
    System.out.println("Origin header count: " + originHeaders.size());

    for (String origin : originHeaders) {
        System.out.println("Origin length: " + origin.length());
        System.out.println("Origin equals expected: " + ALLOWED.equals(origin));
        System.out.println("Origin characters: " +
                origin.chars()
                        .mapToObj(c -> String.format("U+%04X", c))
                        .toList());
    }
}
}
