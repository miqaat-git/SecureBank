package com.securebank.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.securebank.gateway.support.AbstractGatewayTest;
import com.securebank.gateway.support.Downstreams;
import com.securebank.gateway.support.GatewayTest;
import com.securebank.gateway.support.RealmExport;
import com.securebank.gateway.support.TestJwtConfig;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * HTTP contract tests against the real Gateway security chain, route table and filters, with mock upstreams:
 * (1) every public route reaches the path its owning service really serves, (2) internal and raw service paths are
 * unreachable, (3) path-manipulation variants cannot steer a rewrite, (4) headers, bodies and idempotency keys
 * pass through untouched. They do NOT prove the real downstream services accept those paths (covered by the
 * services' own controller tests) and do not involve Keycloak.
 */
@GatewayTest
class PublicApiBoundaryTest extends AbstractGatewayTest {

    private WebTestClient.ResponseSpec call(String method, String uri, String authorization) {
        WebTestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(method)).uri(uri);
        return send(spec, method, authorization);
    }

    private WebTestClient.ResponseSpec call(String method, URI uri, String authorization) {
        WebTestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(method)).uri(uri);
        return send(spec, method, authorization);
    }

    private static WebTestClient.ResponseSpec send(WebTestClient.RequestBodySpec spec, String method,
                                                   String authorization) {
        if (authorization != null) {
            spec.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            return spec.contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange();
        }
        return spec.exchange();
    }

    private static void assertNothingReachedAnyUpstream() {
        for (String name : new String[]{"customer", "account", "transaction", "ledger"}) {
            assertThat(Downstreams.byName(name).requestCount()).as("requests to %s", name).isZero();
        }
    }

    // ------------------------------------------------------------ route matrix

    @ParameterizedTest(name = "{0} {1} -> {2} {3}")
    @CsvSource({
            "GET,/api/customers,customer,/api/v1/customers",
            "GET,/api/customers/42/status,customer,/api/v1/customers/42/status",
            "GET,/api/customers/search?name=asha,customer,/api/v1/customers/search",
            "GET,/api/accounts,account,/api/v1/accounts",
            "GET,/api/accounts/7/balance,account,/api/v1/accounts/7/balance",
            "GET,/api/accounts/7/status,account,/api/v1/accounts/7/status",
            "POST,/api/accounts/7/close,account,/api/v1/accounts/7/close",
            "GET,/api/account-numbers/123456789012,account,/api/v1/account-numbers/123456789012",
            "GET,/api/transactions,transaction,/api/v1/transfers",
            "GET,/api/transactions/abc/history,transaction,/api/v1/transfers/abc/history",
            "GET,/api/transactions/by-idempotency-key/key-1,transaction,/api/v1/transfers/by-idempotency-key/key-1",
            "POST,/api/transactions,transaction,/api/v1/transfers",
            "GET,/api/ledger/journal-entries/by-reference/TRANSFER-ABC,ledger,/api/v1/journal-entries/by-reference/TRANSFER-ABC",
            "GET,/api/ledger/accounts/ACC-1/ledger-lines,ledger,/api/v1/accounts/ACC-1/ledger-lines",
            "GET,/api/ledger/journal-entries,ledger,/api/v1/journal-entries"
    })
    void publicRoutesReachTheRealServicePath(String method, String publicPath, String service, String upstreamPath) {
        call(method, publicPath, TestJwtConfig.BEARER)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo(service)
                .jsonPath("$.method").isEqualTo(method)
                .jsonPath("$.path").isEqualTo(upstreamPath);
        assertThat(Downstreams.byName(service).requestCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} {1} (admin) -> {3}")
    @CsvSource({
            "GET,/api/admin/accounts,account,/api/v1/admin/accounts",
            "POST,/api/admin/accounts/5/freeze,account,/api/v1/admin/accounts/5/freeze",
            "POST,/api/admin/accounts/5/unfreeze,account,/api/v1/admin/accounts/5/unfreeze",
            "POST,/api/admin/accounts/5/approve,account,/api/v1/admin/accounts/5/approve",
            "POST,/api/admin/accounts/5/reject,account,/api/v1/admin/accounts/5/reject"
    })
    void adminRoutesReachTheAdminPathOnlyForAdmins(String method, String publicPath, String service,
                                                   String upstreamPath) {
        call(method, publicPath, TestJwtConfig.BEARER).expectStatus().isForbidden();      // plain customer
        assertThat(Downstreams.ACCOUNT.requestCount()).isZero();

        call(method, publicPath, TestJwtConfig.ADMIN_BEARER)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo(upstreamPath);
        assertThat(Downstreams.ACCOUNT.requestCount()).isEqualTo(1);
    }

    /**
     * The administrator role is the realm role "admin" (-> ROLE_ADMIN): it is what Account Service requires on
     * /api/v1/admin/** and what the export gives dev-admin. Other "admin-like" roles (including the removed
     * transfer-operator, bank-admin and securebank-admin/-staff) are NOT accepted: D2 allows CUSTOMER and ADMIN only.
     */
    @ParameterizedTest(name = "{0} /api/admin/accounts/5/freeze as {1} -> {2}")
    @CsvSource({
            "POST,dev-admin,200",
            "POST,dev-customer,403",
            "POST,service-account-securebank-transaction-service,403"
    })
    void adminRoutesFollowTheExportedRealmUsers(String method, String user, int status) {
        call(method, "/api/admin/accounts/5/freeze", "Bearer " + TestJwtConfig.realmUserToken(user))
                .expectStatus().isEqualTo(status);
        assertThat(Downstreams.ACCOUNT.requestCount()).isEqualTo(status == 200 ? 1 : 0);
    }

    @ParameterizedTest(name = "{0} {1} is administrator-only (transaction service)")
    @CsvSource({
            "POST,/api/admin/funding,/api/v1/admin/funding",
            "POST,/api/transactions/abc/retry,/api/v1/transfers/abc/retry",
            "POST,/api/transactions/abc/reconcile,/api/v1/transfers/abc/reconcile",
            "GET,/api/transactions/abc/operations,/api/v1/transfers/abc/operations",
            "GET,/api/admin/reconciliation/account-balances,/api/v1/admin/reconciliation/account-balances"
    })
    void fundingAndTransferRecoveryRoutesAreAdminOnly(String method, String publicPath, String upstreamPath) {
        call(method, publicPath, TestJwtConfig.BEARER).expectStatus().isForbidden();   // plain customer
        call(method, publicPath, null).expectStatus().isUnauthorized();
        assertThat(Downstreams.byName("transaction").requestCount()).isZero();

        call(method, publicPath, TestJwtConfig.ADMIN_BEARER)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo(upstreamPath);
        assertThat(Downstreams.byName("transaction").requestCount()).isEqualTo(1);
    }

    @Test
    void theOldCustomerFundingRouteIsNotExposedToAnyone() {
        for (String bearer : new String[] {TestJwtConfig.BEARER, TestJwtConfig.ADMIN_BEARER}) {
            call("POST", "/api/transactions/funding", bearer).expectStatus().isForbidden();
        }
        assertThat(Downstreams.byName("transaction").requestCount()).isZero();
    }

    @ParameterizedTest(name = "role set {0} is not an administrator")
    @ValueSource(strings = {"transfer-operator", "bank-admin", "securebank-admin", "securebank-admin,securebank-staff",
            "ledger-reader", "ADMIN-ish"})
    void onlyTheAdminRealmRoleOpensAdminRoutes(String roles) {
        call("GET", "/api/admin/accounts", TestJwtConfig.bearerWithRoles(roles.split(",")))
                .expectStatus().isForbidden();
        assertThat(Downstreams.ACCOUNT.requestCount()).isZero();
        call("GET", "/api/admin/accounts", TestJwtConfig.bearerWithRoles("admin"))
                .expectStatus().isOk();
    }

    @Test
    void aTokenWithBothAdminAndTheServiceRoleIsRefusedOnAdminRoutes() {
        String mixed = TestJwtConfig.bearerWithRoles("admin", "transaction-service");
        for (String path : new String[]{"/api/admin/accounts", "/api/admin/accounts/5/freeze"}) {
            call("POST", path, mixed).expectStatus().isForbidden();
            call("GET", path, mixed).expectStatus().isForbidden();
        }
        assertNothingReachedAnyUpstream();
        // The legitimate administrator is unaffected.
        call("GET", "/api/admin/accounts", TestJwtConfig.ADMIN_BEARER).expectStatus().isOk();
        assertThat(Downstreams.ACCOUNT.requestCount()).isEqualTo(1);
    }

    @Test
    void adminRoleIsDefinedAndHeldOnlyByTheDevAdminInTheRealmExport() {
        assertThat(RealmExport.definedRoles()).contains("admin", "customer")
                .doesNotContain("bank-admin", "transfer-operator", "securebank-staff", "securebank-admin");
        assertThat(RealmExport.rolesOf("dev-admin")).contains("admin");
        assertThat(RealmExport.rolesOf("dev-customer")).doesNotContain("admin");
        assertThat(RealmExport.rolesOf("service-account-securebank-transaction-service")).doesNotContain("admin");
    }

    @Test
    void operatorTransferActionsAreRoutedToTheRealTransferPaths() {
        call("POST", "/api/transactions/t-1/retry", TestJwtConfig.ADMIN_BEARER).expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/api/v1/transfers/t-1/retry");
        call("POST", "/api/transactions/t-1/reconcile", TestJwtConfig.ADMIN_BEARER).expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/api/v1/transfers/t-1/reconcile");
        call("GET", "/api/transactions/t-1/operations", TestJwtConfig.ADMIN_BEARER).expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/api/v1/transfers/t-1/operations");
        // D2: transfer retry, reconciliation and operation inspection are administrator actions.
    }

    // ------------------------------------------------------------ idempotency, headers, body

    @Test
    void transferSubmissionKeepsIdempotencyKeyBodyAndTokenAndIsSentOnce() {
        String body = "{\"sourceAccountId\":\"a\",\"destinationAccountId\":\"b\",\"amount\":10.00,\"currency\":\"INR\"}";

        client.post().uri("/api/transactions")
                .header("Authorization", TestJwtConfig.BEARER)
                .header("Idempotency-Key", "idem-0001-abcd")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.path").isEqualTo("/api/v1/transfers")
                .jsonPath("$.body").isEqualTo(body)
                .jsonPath("$.authorization").isEqualTo(TestJwtConfig.BEARER);

        assertThat(Downstreams.TRANSACTION.lastHeader("Idempotency-Key")).isEqualTo("idem-0001-abcd");
        assertThat(Downstreams.TRANSACTION.postCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------ internal and raw service paths

    @ParameterizedTest(name = "{0} {1} is denied for a valid user")
    @CsvSource({
            "POST,/internal/accounts/1/operations",
            "GET,/internal/accounts/1",
            "GET,/internal/accounts/1/operations/2",
            "POST,/internal/v1/accounts/1/balance-operations",
            "POST,/internal/journal-entries",
            "POST,/internal/journal-entries/TRANSFER-X/reversals",
            "GET,/internal/journal-entries/by-reference/TRANSFER-X",
            "GET,/internal",
            "GET,/internal/",
            "GET,/v3/api-docs",
            "GET,/swagger-ui.html",
            "GET,/swagger-ui/index.html"
    })
    void internalAndDocumentationPathsAreForbiddenEvenWithAValidUserToken(String method, String path) {
        call(method, path, TestJwtConfig.BEARER).expectStatus().isForbidden();
        assertNothingReachedAnyUpstream();
    }

    @ParameterizedTest(name = "{0} {1} requires authentication")
    @CsvSource({
            "POST,/internal/accounts/1/operations",
            "POST,/internal/journal-entries",
            "GET,/v3/api-docs"
    })
    void internalPathsRequireAuthenticationFirst(String method, String path) {
        call(method, path, null).expectStatus().isUnauthorized();
        assertNothingReachedAnyUpstream();
    }

    @Test
    void evenTheServiceAccountTokenCannotUseTheGatewayToReachInternalEndpoints() {
        call("POST", "/internal/accounts/1/operations", TestJwtConfig.SERVICE_BEARER).expectStatus().isForbidden();
        call("POST", "/internal/journal-entries", TestJwtConfig.SERVICE_BEARER).expectStatus().isForbidden();
        call("POST", "/internal/journal-entries/TRANSFER-X/reversals", TestJwtConfig.SERVICE_BEARER)
                .expectStatus().isForbidden();
        assertNothingReachedAnyUpstream();
    }

    @ParameterizedTest(name = "{0} is not a public route")
    @ValueSource(strings = {
            "/INTERNAL/accounts/1/operations",
            "/api/internal/accounts/1/operations",
            "/api/v1/accounts/1",
            "/api/v1/customers",
            "/api/v1/transfers",
            "/api/v1/journal-entries",
            "/api/transfers",
            "/api/journal-entries"
    })
    void rawServicePathsAndLookalikesAreNotRouted(String path) {
        call("GET", path, TestJwtConfig.BEARER).expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND");
        assertNothingReachedAnyUpstream();
    }

    // ------------------------------------------------------------ path manipulation cannot steer a rewrite

    /**
     * Sends the raw attack path exactly as written. A String URI template would normalise it first (collapse "//",
     * percent-encode "%", and treat a leading "//" as an authority), so the sanitizer would never see the attack.
     * A java.net.URI passes through WebTestClient untouched.
     */
    private WebTestClient.ResponseSpec callRaw(String rawPath, String authorization) {
        return call("GET", URI.create("http://localhost" + rawPath), authorization);
    }

    @ParameterizedTest(name = "{0} is rejected before routing")
    @ValueSource(strings = {
            "/api/accounts/../../../internal/accounts/1/operations",
            "/api/accounts/./1",
            "/api/transactions/../../internal/journal-entries",
            "//internal/accounts/1/operations",
            "/api//accounts/1",
            "/api/accounts/1;x=y"
    })
    void dotSegmentsDoubleSlashesAndPathParametersAreRejected(String rawPath) {
        callRaw(rawPath, TestJwtConfig.BEARER).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_PATH");
        callRaw(rawPath, null).expectStatus().isBadRequest();   // rejected before authentication, too
        assertNothingReachedAnyUpstream();
    }

    @ParameterizedTest(name = "{0} is rejected before routing")
    @ValueSource(strings = {
            "/api/accounts/%2e%2e/%2e%2e/internal/accounts/1/operations",
            "/api/accounts/%2E%2E/internal",
            "/api/accounts/..%2finternal/accounts/1",
            "/api/accounts/1%2f..%2f..%2finternal",
            "/api/accounts/1%5c..",
            "/api/accounts/%252e%252e/internal",
            "/api/accounts/1%00"
    })
    void encodedTraversalIsRejected(String rawPath) {
        callRaw(rawPath, TestJwtConfig.BEARER).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_PATH");
        assertNothingReachedAnyUpstream();
    }

    @Test
    void trailingSlashDoesNotBypassOrWidenAnyRule() {
        // Allowed route with a trailing slash is still routed (and still needs a token).
        call("GET", "/api/accounts/7/", null).expectStatus().isUnauthorized();
        // Only real Ledger resource paths are public: the bare prefix is not routed and never reaches the upstream.
        call("GET", "/api/ledger/", TestJwtConfig.BEARER).expectStatus().isNotFound();
        call("GET", "/api/ledger", TestJwtConfig.BEARER).expectStatus().isNotFound();
        // Denied namespaces stay denied with a trailing slash.
        call("POST", "/api/ledger/journal-entries/", TestJwtConfig.BEARER).expectStatus().isForbidden();
        call("GET", "/api/admin/accounts/", TestJwtConfig.BEARER).expectStatus().isForbidden();
        assertThat(Downstreams.LEDGER.requestCount()).isZero();
    }

    // ------------------------------------------------------------ Ledger is read-only; service tokens are not users

    @ParameterizedTest(name = "{0} {1} is not allowed through the gateway")
    @CsvSource({
            "POST,/api/ledger/journal-entries",
            "POST,/api/ledger/journal-entries/00000000-0000-0000-0000-000000000001/reversals",
            "PUT,/api/ledger/journal-entries/1",
            "PATCH,/api/ledger/journal-entries/1",
            "DELETE,/api/ledger/journal-entries/1"
    })
    void ledgerWritesAreNeverPublic(String method, String path) {
        call(method, path, TestJwtConfig.BEARER).expectStatus().isForbidden();
        call(method, path, TestJwtConfig.SERVICE_BEARER).expectStatus().isForbidden();
        call(method, path, TestJwtConfig.ADMIN_BEARER).expectStatus().isForbidden();
        call(method, path, null).expectStatus().isUnauthorized();
        assertThat(Downstreams.LEDGER.requestCount()).isZero();
    }

    @ParameterizedTest(name = "service token on {0} is refused")
    @ValueSource(strings = {"/api/customers/1", "/api/accounts/1", "/api/account-numbers/123",
            "/api/transactions/1", "/api/ledger/journal-entries/by-reference/TRANSFER-X"})
    void serviceAccountTokensAreNotAcceptedOnThePublicApi(String path) {
        call("GET", path, TestJwtConfig.SERVICE_BEARER).expectStatus().isForbidden();
        assertNothingReachedAnyUpstream();
    }

    @Test
    void invalidAndMissingTokensAreRejectedOnEveryPublicRouteWithoutReachingUpstreams() {
        for (String path : new String[]{"/api/customers/1", "/api/accounts/1", "/api/account-numbers/1",
                "/api/admin/accounts", "/api/transactions/1", "/api/ledger/x"}) {
            call("GET", path, null).expectStatus().isUnauthorized();
            call("GET", path, "Bearer not-a-valid-token").expectStatus().isUnauthorized();
        }
        assertNothingReachedAnyUpstream();
    }
}
