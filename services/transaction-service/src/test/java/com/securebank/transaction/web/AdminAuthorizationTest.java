package com.securebank.transaction.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.transaction.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Administrator endpoints with REAL bearer-token processing (decoder stubbed, converter and filter chain real): the
 * human realm role "admin" (Blueprint D2) must open retry, reconcile and operations, and nothing else may.
 * The former "transfer-operator" role no longer exists and grants nothing.
 */
class AdminAuthorizationTest extends AbstractIntegrationTest {

    private static final UUID UNKNOWN = UUID.fromString("99999999-9999-9999-9999-999999999999");

    private String token(String name, String subject, String... realmRoles) {
        Jwt jwt = Jwt.withTokenValue(name).header("alg", "none").subject(subject)
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .claim("customer_id", CUSTOMER)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        when(jwtDecoder.decode(name)).thenReturn(jwt);
        return "Bearer " + name;
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return mockMvc.perform(bearer == null ? request : request.header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private MockHttpServletRequestBuilder[] adminEndpoints() {
        return new MockHttpServletRequestBuilder[] {
                post("/api/v1/transfers/{id}/retry", UNKNOWN),
                post("/api/v1/transfers/{id}/reconcile", UNKNOWN),
                get("/api/v1/transfers/{id}/operations", UNKNOWN)};
    }

    @Test
    void theAdminRoleReachesRetryReconcileAndOperations() throws Exception {
        String admin = token("adm-1", "adm-1", "admin");

        for (MockHttpServletRequestBuilder endpoint : adminEndpoints()) {
            // authorised: the request reaches the controller and fails only because the transfer does not exist
            call(endpoint, admin).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
        }
    }

    @Test
    void everyOtherIdentityIsForbiddenFromAdminEndpoints() throws Exception {
        String[] others = {
                token("cust", "user-1", "customer"),
                token("legacy", "ops", "transfer-operator", "transfer_operator", "securebank-admin", "bank-admin"),
                token("ledger", "led", "ledger-reader", "ledger-writer", "ledger-reverser"),
                token("machine", "svc", "transaction-service", "account-service", "ledger-writer"),
                token("lookalike", "x", "admins", "administrator", "operator"),
                token("none", "nobody")};

        for (String bearer : others) {
            for (MockHttpServletRequestBuilder endpoint : adminEndpoints()) {
                call(endpoint, bearer).andExpect(status().isForbidden());
            }
            call(post("/api/v1/admin/funding").header("Idempotency-Key", "test-key-deny-1")
                    .contentType("application/json").content(FUNDING_BODY), bearer)
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void anonymousCallersAreUnauthenticated() throws Exception {
        for (MockHttpServletRequestBuilder endpoint : adminEndpoints()) {
            call(endpoint, null).andExpect(status().isUnauthorized());
        }
        call(post("/api/v1/admin/funding").header("Idempotency-Key", "test-key-anon-1")
                .contentType("application/json").content(FUNDING_BODY), null).andExpect(status().isUnauthorized());
    }

    @Test
    void theCustomerFundingEndpointNoLongerExistsForAnyone() throws Exception {
        call(post("/api/v1/transfers/funding").header("Idempotency-Key", "test-key-old-1")
                .contentType("application/json").content(FUNDING_BODY), token("c2", "user-2", "customer"))
                .andExpect(status().isForbidden());   // no such route any more: denied by the deny-all fallback
    }

    @Test
    void adminRoleDoesNotAllowInitiatingTransfersWithoutTheCustomerRole() throws Exception {
        String admin = token("adm-3", "adm-3", "admin");

        call(post("/api/v1/transfers").header("Idempotency-Key", "test-key-admin-1")
                .contentType("application/json")
                .content("{\"sourceAccountId\":\"" + SRC + "\",\"destinationAccountId\":\"" + DST
                        + "\",\"amount\":1.00,\"currency\":\"INR\"}"), admin)
                .andExpect(status().isForbidden());
    }

    private static final String FUNDING_BODY = "{\"targetAccountId\":\"" + DST
            + "\",\"amount\":5.00,\"currency\":\"INR\",\"fundingReference\":\"REF-DENY-0001\","
            + "\"reason\":\"test\"}";
}
