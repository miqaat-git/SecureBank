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
 * Operator endpoints with REAL bearer-token processing (decoder stubbed, converter and filter chain real): the
 * Keycloak realm role "transfer-operator" must open retry, reconcile and operations, and nothing else may.
 */
class TransferOperatorAuthorizationTest extends AbstractIntegrationTest {

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

    private MockHttpServletRequestBuilder[] operatorEndpoints() {
        return new MockHttpServletRequestBuilder[] {
                post("/api/v1/transfers/{id}/retry", UNKNOWN),
                post("/api/v1/transfers/{id}/reconcile", UNKNOWN),
                get("/api/v1/transfers/{id}/operations", UNKNOWN)};
    }

    @Test
    void theTransferOperatorRoleReachesRetryReconcileAndOperations() throws Exception {
        String operator = token("op-1", "ops-1", "transfer-operator");

        for (MockHttpServletRequestBuilder endpoint : operatorEndpoints()) {
            // authorised: the request reaches the controller and fails only because the transfer does not exist
            call(endpoint, operator).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
        }
    }

    @Test
    void underscoreSpellingOfTheRoleIsAlsoAccepted() throws Exception {
        String operator = token("op-2", "ops-2", "transfer_operator");

        call(post("/api/v1/transfers/{id}/retry", UNKNOWN), operator).andExpect(status().isNotFound());
    }

    @Test
    void everyOtherIdentityIsForbiddenFromOperatorEndpoints() throws Exception {
        String[] others = {
                token("cust", "user-1", "customer"),
                token("admin", "adm", "admin", "securebank-admin", "securebank-staff", "bank-admin"),
                token("ledger", "led", "ledger-reader", "ledger-writer", "ledger-reverser"),
                token("machine", "svc", "transaction-service", "ledger-writer", "ledger-reverser"),
                token("lookalike", "x", "transfer-operators", "operator", "transfer"),
                token("none", "nobody")};

        for (String bearer : others) {
            for (MockHttpServletRequestBuilder endpoint : operatorEndpoints()) {
                call(endpoint, bearer).andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void anonymousCallersAreUnauthenticated() throws Exception {
        for (MockHttpServletRequestBuilder endpoint : operatorEndpoints()) {
            call(endpoint, null).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void operatorRoleDoesNotAllowInitiatingTransfersWithoutTheCustomerRole() throws Exception {
        String operator = token("op-3", "ops-3", "transfer-operator");

        call(post("/api/v1/transfers").header("Idempotency-Key", "test-key-operator-1")
                .contentType("application/json")
                .content("{\"sourceAccountId\":\"" + SRC + "\",\"destinationAccountId\":\"" + DST
                        + "\",\"amount\":1.00,\"currency\":\"INR\"}"), operator)
                .andExpect(status().isForbidden());
    }
}
