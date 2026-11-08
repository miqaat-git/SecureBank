package com.securebank.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.securebank.gateway.support.AbstractGatewayTest;
import com.securebank.gateway.support.Downstreams;
import com.securebank.gateway.support.GatewayTest;
import com.securebank.gateway.support.TestJwtConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Machine-only roles (transaction-service, ledger-writer, ledger-reverser) must never open the public or admin
 * API, alone or combined with ordinary roles, whatever the letter case. The Gateway maps every realm role to
 * ROLE_&lt;UPPER_CASE&gt;, so the check is case-insensitive in practice and is pinned here as such.
 * Fixtures are synthetic tokens produced by {@link TestJwtConfig}; no Keycloak is involved.
 */
@GatewayTest
class ServiceRoleBoundaryTest extends AbstractGatewayTest {

    private static final String[] PUBLIC_GETS = {
            "/api/customers/1", "/api/accounts/1", "/api/account-numbers/123",
            "/api/transactions/1", "/api/ledger/journal-entries/by-reference/TRANSFER-X"
    };

    private WebTestClient.ResponseSpec get(String path, String bearer) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer).exchange();
    }

    private static void assertNothingReachedAnyUpstream() {
        for (String name : new String[]{"customer", "account", "transaction", "ledger"}) {
            assertThat(Downstreams.byName(name).requestCount()).as("requests to %s", name).isZero();
        }
    }

    @ParameterizedTest(name = "roles [{0}] are refused on the public and admin API")
    @ValueSource(strings = {
            "ledger-writer", "ledger-reverser", "transaction-service",
            "customer,ledger-writer", "customer,ledger-reverser", "customer,transaction-service",
            "admin,ledger-writer", "admin,ledger-reverser", "admin,transaction-service",
            "admin,customer,ledger-writer,ledger-reverser,transaction-service",
            "account-service", "customer,account-service", "admin,account-service", "ACCOUNT-SERVICE",
            "LEDGER-WRITER", "Ledger-Reverser", "Transaction-Service", "admin,Transaction-Service"
    })
    void machineOnlyRolesAreRefusedEverywhereExceptTheirOwnServices(String roles) {
        String bearer = TestJwtConfig.bearerWithRoles(roles.split(","));
        for (String path : PUBLIC_GETS) {
            get(path, bearer).expectStatus().isForbidden();
        }
        get("/api/admin/accounts", bearer).expectStatus().isForbidden();
        client.post().uri("/api/admin/accounts/5/freeze").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isForbidden();
        client.post().uri("/api/transactions").header(HttpHeaders.AUTHORIZATION, bearer)
                .header("Idempotency-Key", "role-test-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isForbidden();
        assertNothingReachedAnyUpstream();
    }

    @Test
    void ledgerReaderIsNotAMachineRoleAndStillReadsTheLedgerThroughTheGateway() {
        get("/api/ledger/journal-entries/by-reference/TRANSFER-X",
                TestJwtConfig.bearerWithRoles("customer", "ledger-reader")).expectStatus().isOk();
        assertThat(Downstreams.LEDGER.requestCount()).isEqualTo(1);
    }

    @Test
    void anAuthenticatedTokenWithNoRolesIsNotAnAdministrator() {
        String noRoles = TestJwtConfig.bearerWithRoles();
        get("/api/admin/accounts", noRoles).expectStatus().isForbidden();
        assertThat(Downstreams.ACCOUNT.requestCount()).isZero();
        // Ordinary routes only require authentication at the Gateway; the owning service enforces its own roles.
        get("/api/accounts/1", noRoles).expectStatus().isOk();
        assertThat(Downstreams.ACCOUNT.requestCount()).isEqualTo(1);
    }
}
