package com.securebank.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.securebank.gateway.support.AbstractGatewayTest;
import com.securebank.gateway.support.Downstreams;
import com.securebank.gateway.support.GatewayTest;
import com.securebank.gateway.support.TestJwtConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@GatewayTest
class GatewaySecurityTest extends AbstractGatewayTest {

    @ParameterizedTest
    @ValueSource(strings = {"/api/customers/1", "/api/accounts/1", "/api/transactions/1", "/api/ledger/1"})
    void apiRoutesRequireAuthentication(String path) {
        client.get().uri(path).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.status").isEqualTo(401);

        assertThat(Downstreams.CUSTOMER.requestCount() + Downstreams.ACCOUNT.requestCount()
                + Downstreams.TRANSACTION.requestCount() + Downstreams.LEDGER.requestCount()).isZero();
    }

    @Test
    void invalidTokenIsRejectedWithoutDetailAndNotForwarded() {
        client.get().uri("/api/customers/1")
                .header("Authorization", "Bearer not-a-valid-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.message").value(m -> assertThat(m.toString()).doesNotContain("rejected by test decoder"));

        assertThat(Downstreams.CUSTOMER.requestCount()).isZero();
    }

    @Test
    void basicAuthIsNotAccepted() {
        client.get().uri("/api/accounts/1")
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .exchange()
                .expectStatus().isUnauthorized();
        assertThat(Downstreams.ACCOUNT.requestCount()).isZero();
    }

    @Test
    void validTokenIsForwardedWithBearerPreserved() {
        client.get().uri("/api/accounts/1")
                .header("Authorization", TestJwtConfig.BEARER)
                .exchange()
                .expectStatus().isOk();
        assertThat(Downstreams.ACCOUNT.lastHeader("Authorization")).isEqualTo(TestJwtConfig.BEARER);
    }

    @Test
    void spoofedIdentityHeadersAreStrippedBeforeForwarding() {
        client.get().uri("/api/accounts/1")
                .header("Authorization", TestJwtConfig.BEARER)
                .header("X-User-Id", "admin")
                .header("X-User-Roles", "ROLE_ADMIN")
                .header("X-Customer-Id", "someone-else")
                .header("X-SecureBank-User", "root")
                .header("X-Internal-Role", "admin")
                .exchange()
                .expectStatus().isOk();

        assertThat(Downstreams.ACCOUNT.lastHeader("X-User-Id")).isNull();
        assertThat(Downstreams.ACCOUNT.lastHeader("X-User-Roles")).isNull();
        assertThat(Downstreams.ACCOUNT.lastHeader("X-Customer-Id")).isNull();
        assertThat(Downstreams.ACCOUNT.lastHeader("X-SecureBank-User")).isNull();
        assertThat(Downstreams.ACCOUNT.lastHeader("X-Internal-Role")).isNull();
        assertThat(Downstreams.ACCOUNT.lastHeader("Authorization")).isEqualTo(TestJwtConfig.BEARER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthEndpointsArePublicAndExposeNoDetails(String path) {
        client.get().uri(path).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist()
                .jsonPath("$.details").doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/beans", "/actuator/metrics", "/actuator/mappings",
            "/actuator/gateway/routes"})
    void otherManagementEndpointsAreNotPublic(String path) {
        client.get().uri(path).exchange()
                .expectStatus().isUnauthorized();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/beans", "/actuator/gateway/routes"})
    void otherManagementEndpointsAreForbiddenEvenWhenAuthenticated(String path) {
        client.get().uri(path)
                .header("Authorization", TestJwtConfig.BEARER)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void healthEndpointDoesNotAcceptNonGetMethodsAnonymously() {
        client.post().uri("/actuator/health").exchange()
                .expectStatus().isUnauthorized();
    }
}
