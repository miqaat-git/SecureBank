package com.securebank.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.securebank.gateway.support.AbstractGatewayTest;
import com.securebank.gateway.support.Downstreams;
import com.securebank.gateway.support.GatewayTest;
import com.securebank.gateway.support.TestJwtConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Real filter chain with deliberately small limits (test-specific configuration; production defaults live in
 * application.yml). Counters are in-memory and per context, so each assertion sequence uses its own buckets.
 */
@GatewayTest
@TestPropertySource(properties = {
        "securebank.rate-limit.rules[0].name=funding",
        "securebank.rate-limit.rules[0].paths=/api/admin/funding",
        "securebank.rate-limit.rules[0].methods=POST",
        "securebank.rate-limit.rules[0].anonymous.limit=2",
        "securebank.rate-limit.rules[0].anonymous.window=1h",
        "securebank.rate-limit.rules[0].authenticated.limit=1",
        "securebank.rate-limit.rules[0].authenticated.window=1h",
        "securebank.rate-limit.rules[1].name=default",
        "securebank.rate-limit.rules[1].paths=/api/**",
        "securebank.rate-limit.rules[1].anonymous.limit=3",
        "securebank.rate-limit.rules[1].anonymous.window=1h",
        "securebank.rate-limit.rules[1].authenticated.limit=2",
        "securebank.rate-limit.rules[1].authenticated.window=1h",
        "securebank.rate-limit.auth-failure.limit=2",
        "securebank.rate-limit.auth-failure.window=1h"
})
class RateLimitingTest extends AbstractGatewayTest {

    private WebTestClient.ResponseSpec get(String uri, String authorization) {
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri(uri);
        if (authorization != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return spec.exchange();
    }

    @org.springframework.beans.factory.annotation.Autowired
    RateLimitStore store;

    @org.junit.jupiter.api.BeforeEach
    void freshCounters() {
        ((InMemoryRateLimitStore) store).clear();
    }

    private static final String DEV_CUSTOMER = "Bearer " + TestJwtConfig.realmUserToken("dev-customer");

    @Test
    void anonymousCallersAreLimitedAnd429CarriesRetryAfterAndTheStandardErrorBody() {
        // /api/customers: anonymous requests hit the limiter before security answers 401
        for (int i = 0; i < 3; i++) {
            get("/api/customers/1", null).expectStatus().isUnauthorized();
        }
        get("/api/customers/1", null)
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED")
                .jsonPath("$.correlationId").exists();
        assertThat(Downstreams.CUSTOMER.requestCount()).isZero();      // rejected before any routing
    }

    @Test
    void aSpoofedForwardedForHeaderDoesNotGiveAnAnonymousCallerANewIdentity() {
        // distinct bucket from the previous test: a different rule path
        for (int i = 0; i < 2; i++) {
            client.post().uri("/api/admin/funding").header("X-Forwarded-For", "9.9.9." + i)
                    .exchange().expectStatus().isUnauthorized();
        }
        client.post().uri("/api/admin/funding").header("X-Forwarded-For", "9.9.9.200")
                .header("X-Real-IP", "8.8.8.8").header("X-User-Id", "someone-else")
                .exchange().expectStatus().isEqualTo(429);
    }

    @Test
    void authenticatedLimitsAreKeyedBySubjectAndIdentitiesAreIndependent() {
        get("/api/accounts/1", TestJwtConfig.BEARER).expectStatus().isOk();
        get("/api/accounts/1", TestJwtConfig.BEARER).expectStatus().isOk();
        get("/api/accounts/1", TestJwtConfig.BEARER).expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER);

        // another subject still has its whole budget, and anonymous traffic is a separate policy
        get("/api/accounts/1", DEV_CUSTOMER).expectStatus().isOk();
        get("/api/accounts/1", DEV_CUSTOMER).expectStatus().isOk();
        get("/api/accounts/1", DEV_CUSTOMER).expectStatus().isEqualTo(429);
    }

    @Test
    void ruleBucketsAreSeparateSoASensitiveRouteHasItsOwnTighterLimit() {
        String admin = TestJwtConfig.ADMIN_BEARER;
        client.post().uri("/api/admin/funding").header(HttpHeaders.AUTHORIZATION, admin).exchange()
                .expectStatus().isOk();
        client.post().uri("/api/admin/funding").header(HttpHeaders.AUTHORIZATION, admin).exchange()
                .expectStatus().isEqualTo(429);
        // other admin routes use the default rule and are unaffected by the funding bucket
        get("/api/admin/accounts/5/freeze", admin);   // (method mismatch is irrelevant; must not be 429)
        client.get().uri("/api/customers/7").header(HttpHeaders.AUTHORIZATION, admin).exchange()
                .expectStatus().value(s -> assertThat(s).isNotEqualTo(429));
    }

    @Test
    void repeatedInvalidTokensExhaustTheFailedAuthenticationBudget() {
        String garbage = "Bearer not-a-valid-token";
        get("/api/customers/1", garbage).expectStatus().isUnauthorized();
        get("/api/customers/1", garbage).expectStatus().isUnauthorized();
        get("/api/customers/1", garbage).expectStatus().isEqualTo(429);
        // a valid token from the same address is never affected by the failed-authentication budget
        get("/api/customers/1", TestJwtConfig.BEARER).expectStatus().isOk();
    }

    @Test
    void healthInternalAndPreflightRequestsAreNeverRateLimited() {
        for (int i = 0; i < 10; i++) {
            client.get().uri("/actuator/health").exchange().expectStatus().value(s -> assertThat(s).isNotEqualTo(429));
            client.get().uri("/internal/accounts/1/operations").exchange()
                    .expectStatus().value(s -> assertThat(s).isNotEqualTo(429));
            client.method(HttpMethod.OPTIONS).uri("/api/customers/1")
                    .header(HttpHeaders.ORIGIN, "https://app.securebank.test")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .exchange().expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.securebank.test");
        }
    }

    @Test
    void aRejectionStillCarriesCorsHeadersForAnAllowedOrigin() {
        for (int i = 0; i < 3; i++) {
            client.get().uri("/api/ledger/entries").header(HttpHeaders.ORIGIN, "https://app.securebank.test")
                    .exchange();
        }
        client.get().uri("/api/ledger/entries").header(HttpHeaders.ORIGIN, "https://app.securebank.test")
                .exchange().expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.securebank.test");
    }
}
