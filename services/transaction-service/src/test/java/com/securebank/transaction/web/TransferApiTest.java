package com.securebank.transaction.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.transaction.AbstractIntegrationTest;
import com.securebank.transaction.client.AccountOperationRequest;
import com.securebank.transaction.client.AccountOperationType;
import com.securebank.transaction.client.RemoteResult;
import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

class TransferApiTest extends AbstractIntegrationTest {

    private static RequestPostProcessor customerJwt(String customerId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                .jwt(j -> j.subject("user-" + customerId).claim("customer_id", customerId));
    }

    private static RequestPostProcessor operatorJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_TRANSFER_OPERATOR")).jwt(j -> j.subject("ops-1"));
    }

    private static String body(String amount, String currency) {
        return "{\"sourceAccountId\":\"" + SRC + "\",\"destinationAccountId\":\"" + DST + "\",\"amount\":"
                + amount + ",\"currency\":\"" + currency + "\"}";
    }

    private ResultActions initiate(String key, String json, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post("/api/v1/transfers").with(auth).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID createCompleted(String key) throws Exception {
        stubEverythingSucceeds();
        return transferService.initiate(key, cmd("10.00"), customer()).transfer().getId();
    }

    // ------------------------------------------------------------------ authentication / authorization

    @Test
    void unauthenticatedRequestsGet401JsonError() throws Exception {
        mockMvc.perform(post("/api/v1/transfers").header("Idempotency-Key", "test-key-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(body("10.00", "INR")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/transfers/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotUsePrivilegedOperations() throws Exception {
        UUID id = createCompleted("test-key-priv-1");
        mockMvc.perform(post("/api/v1/transfers/" + id + "/retry").with(customerJwt(CUSTOMER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/transfers/" + id + "/reconcile").with(customerJwt(CUSTOMER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/transfers/" + id + "/operations").with(customerJwt(CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCannotInitiateTransfersAndTokenWithoutRolesIsForbidden() throws Exception {
        initiate("test-key-role-1", body("10.00", "INR"), operatorJwt()).andExpect(status().isForbidden());
        initiate("test-key-role-2", body("10.00", "INR"), jwt()).andExpect(status().isForbidden());
    }

    @Test
    void customerTokenWithoutCustomerIdClaimIsRejectedFailClosed() throws Exception {
        stubEverythingSucceeds();
        initiate("test-key-noclaim", body("10.00", "INR"),
                jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")).jwt(j -> j.subject("u")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUSTOMER_IDENTITY_UNAVAILABLE"));
        assertThat(transferRepo.count()).isZero();
    }

    @Test
    void identityComesFromTheTokenNeverFromTheRequestBody() throws Exception {
        stubEverythingSucceeds();
        String json = "{\"customerId\":\"EVIL\",\"initiatedBy\":\"admin\",\"sourceAccountId\":\"" + SRC
                + "\",\"destinationAccountId\":\"" + DST + "\",\"amount\":5.00,\"currency\":\"INR\"}";

        initiate("test-key-identity", json, customerJwt(CUSTOMER)).andExpect(status().isCreated());

        Transfer t = transferRepo.findByIdempotencyKey("test-key-identity").orElseThrow();
        assertThat(t.getInitiatorCustomerId()).isEqualTo(CUSTOMER);
        assertThat(t.getInitiatedBy()).isEqualTo("user-" + CUSTOMER);
    }

    // ------------------------------------------------------------------ request validation

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mockMvc.perform(post("/api/v1/transfers").with(customerJwt(CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON).content(body("10.00", "INR")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void invalidBodiesGet400WithFieldDetails() throws Exception {
        initiate("test-key-val-1", body("-5", "INR"), customerJwt(CUSTOMER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("amount"));
        initiate("test-key-val-2", body("10.001", "INR"), customerJwt(CUSTOMER)).andExpect(status().isBadRequest());
        initiate("test-key-val-3", body("10.00", "RUPEES"), customerJwt(CUSTOMER)).andExpect(status().isBadRequest());
        initiate("test-key-val-4", "{\"amount\":10}", customerJwt(CUSTOMER)).andExpect(status().isBadRequest());
        initiate("test-key-val-5", "not json", customerJwt(CUSTOMER))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(transferRepo.count()).isZero();
    }

    @Test
    void businessRuleViolationsGet422() throws Exception {
        initiate("test-key-biz-01", body("10.00", "USD"), customerJwt(CUSTOMER))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
        initiate("test-key-biz-02", body("2000000.00", "INR"), customerJwt(CUSTOMER))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("AMOUNT_EXCEEDS_LIMIT"));
        String same = "{\"sourceAccountId\":\"" + SRC + "\",\"destinationAccountId\":\"" + SRC
                + "\",\"amount\":1.00,\"currency\":\"INR\"}";
        initiate("test-key-biz-03", same, customerJwt(CUSTOMER))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));
        initiate("bad key!", body("1.00", "INR"), customerJwt(CUSTOMER))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    // ------------------------------------------------------------------ initiate semantics

    @Test
    void createdReplayedAndConflictingRequestsUseDistinctStatusCodes() throws Exception {
        stubEverythingSucceeds();

        String first = initiate("test-key-http-1", body("100.00", "INR"), customerJwt(CUSTOMER))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(first, "$.transferId");

        initiate("test-key-http-1", body("100.00", "INR"), customerJwt(CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.transferId").value(id));

        initiate("test-key-http-1", body("101.00", "INR"), customerJwt(CUSTOMER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(transferRepo.count()).isEqualTo(1);
    }

    @Test
    void inProgressTransferReturns202() throws Exception {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.post(any())).thenReturn(RemoteResult.retryable("SERVICE_UNAVAILABLE"));

        initiate("test-key-http-2", body("10.00", "INR"), customerJwt(CUSTOMER))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void businessFailureIsReportedInTheResourceNotAsAnHttpError() throws Exception {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.rejected("INSUFFICIENT_FUNDS"));

        initiate("test-key-http-3", body("10.00", "INR"), customerJwt(CUSTOMER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void unexpectedErrorsReturnGeneric500WithoutLeakingDetails() throws Exception {
        when(accounts.findAccount(any())).thenThrow(new IllegalStateException("secret-internal-detail jdbc:mysql://x"));

        initiate("test-key-http-4", body("10.00", "INR"), customerJwt(CUSTOMER))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("secret-internal-detail"))))
                .andExpect(content().string(not(containsString("jdbc"))));
    }

    // ------------------------------------------------------------------ reads and ownership

    @Test
    void ownerAndOperatorCanReadButOtherCustomersGet404() throws Exception {
        UUID id = createCompleted("test-key-read-1");

        mockMvc.perform(get("/api/v1/transfers/" + id).with(customerJwt(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transferId").value(id.toString()));
        mockMvc.perform(get("/api/v1/transfers/" + id).with(operatorJwt())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/transfers/" + id).with(customerJwt("CUST-OTHER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/transfers/" + id + "/history").with(customerJwt("CUST-OTHER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/transfers/" + UUID.randomUUID()).with(customerJwt(CUSTOMER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/transfers/not-a-uuid").with(customerJwt(CUSTOMER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lookupByIdempotencyKeyRespectsOwnership() throws Exception {
        createCompleted("test-key-bykey-1");

        mockMvc.perform(get("/api/v1/transfers/by-idempotency-key/test-key-bykey-1").with(customerJwt(CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.idempotencyKey").value("test-key-bykey-1"));
        mockMvc.perform(get("/api/v1/transfers/by-idempotency-key/test-key-bykey-1").with(customerJwt("CUST-OTHER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void historyEndpointReturnsOrderedPersistedTransitions() throws Exception {
        UUID id = createCompleted("test-key-hist-1");

        mockMvc.perform(get("/api/v1/transfers/" + id + "/history").with(customerJwt(CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].newStatus").value("INITIATED"))
                .andExpect(jsonPath("$[0].previousStatus").doesNotExist())
                .andExpect(jsonPath("$[1].newStatus").value("VALIDATING"))
                .andExpect(jsonPath("$[2].newStatus").value("PROCESSING"))
                .andExpect(jsonPath("$[3].newStatus").value("COMPLETED"))
                .andExpect(jsonPath("$[3].previousStatus").value("PROCESSING"));
    }

    @Test
    void listIsPaginatedFilteredAndScopedToTheCaller() throws Exception {
        stubEverythingSucceeds();
        for (int i = 1; i <= 3; i++) {
            transferService.initiate("test-key-list-" + i, cmd(i + ".00"), customer());
        }
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.rejected("INSUFFICIENT_FUNDS"));
        transferService.initiate("test-key-list-4", cmd("9.00"), customer());

        mockMvc.perform(get("/api/v1/transfers?size=2&page=0").with(customerJwt(CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/transfers?status=FAILED").with(customerJwt(CUSTOMER)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].errorCode").value("INSUFFICIENT_FUNDS"));
        mockMvc.perform(get("/api/v1/transfers?sourceAccountId=" + UUID.randomUUID()).with(operatorJwt()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/transfers").with(customerJwt("CUST-OTHER")))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/transfers").with(operatorJwt()))
                .andExpect(jsonPath("$.totalElements").value(4));
        mockMvc.perform(get("/api/v1/transfers?size=100000").with(operatorJwt()))
                .andExpect(jsonPath("$.size").value(100));
    }

    // ------------------------------------------------------------------ operator actions

    @Test
    void operatorSeesWorkflowOperations() throws Exception {
        UUID id = createCompleted("test-key-ops-1");

        mockMvc.perform(get("/api/v1/transfers/" + id + "/operations").with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].type").value("DEBIT_SOURCE"))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"));
    }

    @Test
    void retryAndReconcileEnforceStateRules() throws Exception {
        UUID id = createCompleted("test-key-act-1");

        mockMvc.perform(post("/api/v1/transfers/" + id + "/retry").with(operatorJwt()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mockMvc.perform(post("/api/v1/transfers/" + id + "/reconcile").with(operatorJwt()))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/transfers/" + UUID.randomUUID() + "/retry").with(operatorJwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void operatorRetryResumesAnIncompleteTransfer() throws Exception {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.post(any())).thenReturn(RemoteResult.retryable("SERVICE_UNAVAILABLE"))
                .thenReturn(RemoteResult.succeeded());
        when(ledger.reverse(any())).thenReturn(RemoteResult.succeeded());
        UUID id = transferService.initiate("test-key-act-2", cmd("10.00"), customer()).transfer().getId();
        assertThat(store.require(id).getStatus()).isEqualTo(TransferStatus.PROCESSING);

        mockMvc.perform(post("/api/v1/transfers/" + id + "/retry").with(operatorJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void operatorReconcilesAmbiguousTransferOverHttp() throws Exception {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenAnswer(inv -> {
            AccountOperationRequest r = inv.getArgument(0);
            return r.type() == AccountOperationType.DEBIT ? RemoteResult.unknown("IO_ERROR_OR_TIMEOUT")
                    : RemoteResult.succeeded();
        });
        when(accounts.getOperation(any(), any())).thenReturn(RemoteResult.unknown("IO_ERROR_OR_TIMEOUT"));
        when(ledger.post(any())).thenReturn(RemoteResult.succeeded());
        UUID id = transferService.initiate("test-key-act-3", cmd("10.00"), customer()).transfer().getId();
        processor.process(id);
        processor.process(id);
        assertThat(store.require(id).getStatus()).isEqualTo(TransferStatus.RECONCILIATION_REQUIRED);

        mockMvc.perform(post("/api/v1/transfers/" + id + "/retry").with(operatorJwt()))
                .andExpect(status().isConflict());

        when(accounts.getOperation(any(), any())).thenReturn(RemoteResult.succeeded());
        mockMvc.perform(post("/api/v1/transfers/" + id + "/reconcile").with(operatorJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
