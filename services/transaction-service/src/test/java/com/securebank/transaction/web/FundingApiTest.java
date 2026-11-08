package com.securebank.transaction.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.transaction.AbstractIntegrationTest;
import com.securebank.transaction.client.AccountOperationType;
import com.securebank.transaction.client.AccountView;
import com.securebank.transaction.client.RemoteResult;
import com.securebank.transaction.domain.AuditEvent;
import com.securebank.transaction.domain.FundingSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP contract of POST /api/v1/admin/funding (Blueprint D7): administrator-only simulated funding, no source from
 * the caller, atomic/idempotent, audited.
 */
class FundingApiTest extends AbstractIntegrationTest {

    private static final String ADMIN_SUB = "admin-1";

    private static RequestPostProcessor adminJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")).jwt(j -> j.subject(ADMIN_SUB));
    }

    private static RequestPostProcessor customerJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                .jwt(j -> j.subject("user-" + CUSTOMER).claim("customer_id", CUSTOMER));
    }

    private void stub(String status) {
        when(accounts.findAccount(SRC)).thenReturn(Optional.of(new AccountView(SRC, CUSTOMER, status, "INR")));
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.post(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.reverse(any())).thenReturn(RemoteResult.succeeded());
    }

    private static String body(String amount, String reference) {
        return "{\"targetAccountId\":\"" + SRC + "\",\"amount\":" + amount + ",\"currency\":\"INR\","
                + "\"fundingReference\":\"" + reference + "\",\"reason\":\"Simulated onboarding balance\"}";
    }

    private ResultActions fund(String key, String json, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/funding").with(auth).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private List<AuditEvent> audits(String transferId) {
        return auditRepo.findByTransferIdOrderByIdAsc(UUID.fromString(transferId));
    }

    @Test
    void adminFundsAnActiveAccountWithOneLedgerEntryAndOneCreditAndAudit() throws Exception {
        stub("ACTIVE");

        String id = fund("api-fund-0001", body("100.00", "REF-0000-0001"), adminJwt())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("FUNDING"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.sourceAccountId").value(FundingSource.CONTRA_ACCOUNT.toString()))
                .andExpect(jsonPath("$.destinationAccountId").value(SRC.toString()))
                .andExpect(jsonPath("$.fundingReference").value("REF-0000-0001"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"transferId\":\"([^\"]+)\".*", "$1");

        verify(ledger, times(1)).post(any());                       // exactly one ledger entry per funding event
        verify(accounts, times(1)).applyOperation(any());           // exactly one balance delta (the credit)
        verify(accounts, never()).applyOperation(org.mockito.ArgumentMatchers.argThat(
                r -> r.type() != AccountOperationType.CREDIT));     // no debit leg: there is no source account
        assertThat(audits(id)).extracting(AuditEvent::getEventType)
                .containsExactly("FUNDING_REQUESTED", "FUNDING_POSTED");
        assertThat(audits(id)).allSatisfy(a -> {
            assertThat(a.getActorSubject()).isEqualTo(ADMIN_SUB);
            assertThat(a.getActorRole()).isEqualTo("ADMIN");
            assertThat(a.getReason()).isEqualTo("Simulated onboarding balance");
        });
    }

    @Test
    void replayReturnsTheOriginalEventAndAChangedPayloadIs409AndAudited() throws Exception {
        stub("ACTIVE");
        fund("api-fund-0002", body("100.00", "REF-0000-0002"), adminJwt()).andExpect(status().isCreated());

        fund("api-fund-0002", body("100.00", "REF-0000-0002"), adminJwt())
                .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replay", "true"));
        fund("api-fund-0002", body("101.00", "REF-0000-0002"), adminJwt())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        String other = SRC.toString().replace("1", "3");
        fund("api-fund-0002", body("100.00", "REF-0000-0002").replace(SRC.toString(), other), adminJwt())
                .andExpect(status().isConflict());

        verify(ledger, times(1)).post(any());
        assertThat(auditRepo.findByEventTypeOrderByIdAsc("FUNDING_CONFLICT"))
                .filteredOn(a -> "IDEMPOTENCY_KEY_REUSED".equals(a.getDetailCode())
                        && ADMIN_SUB.equals(a.getActorSubject()))
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void aReusedFundingReferenceWithANewKeyIs409AndMovesNothing() throws Exception {
        stub("ACTIVE");
        fund("api-fund-0003", body("10.00", "REF-0000-0003"), adminJwt()).andExpect(status().isCreated());

        fund("api-fund-0004", body("10.00", "REF-0000-0003"), adminJwt())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FUNDING_REFERENCE_REUSED"));
        verify(ledger, times(1)).post(any());
    }

    @Test
    void customersAndEveryNonAdminIdentityCannotFundAndNothingMoves() throws Exception {
        stub("ACTIVE");
        RequestPostProcessor machine = jwt().authorities(new SimpleGrantedAuthority("ROLE_TRANSACTION_SERVICE"))
                .jwt(j -> j.subject("svc"));
        RequestPostProcessor legacy = jwt().authorities(new SimpleGrantedAuthority("ROLE_TRANSFER_OPERATOR"))
                .jwt(j -> j.subject("ops-1"));
        RequestPostProcessor noRole = jwt().jwt(j -> j.subject("nobody"));

        for (RequestPostProcessor who : new RequestPostProcessor[] {customerJwt(), machine, legacy, noRole}) {
            fund("api-fund-0005", body("5.00", "REF-0000-0005"), who).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/admin/funding").header("Idempotency-Key", "api-fund-0006")
                .contentType(MediaType.APPLICATION_JSON).content(body("5.00", "REF-0000-0006")))
                .andExpect(status().isUnauthorized());
        // the old customer-facing route is gone
        mockMvc.perform(post("/api/v1/transfers/funding").with(customerJwt()).header("Idempotency-Key", "api-fund-0007")
                .contentType(MediaType.APPLICATION_JSON).content(body("5.00", "REF-0000-0007")))
                .andExpect(status().isForbidden());
        verify(accounts, never()).applyOperation(any());
        verify(ledger, never()).post(any());
    }

    @Test
    void theServiceLayerRejectsANonAdminCallerEvenIfTheWebLayerWereBypassed() {
        stub("ACTIVE");
        com.securebank.transaction.service.FundingCommand cmd = new com.securebank.transaction.service.FundingCommand(
                SRC, new java.math.BigDecimal("5.00"), "INR", "REF-0000-0008", "reason");

        assertThatThrownBy(() -> transferService.initiateFunding("svc-fund-0001", cmd, customer()))
                .hasMessageContaining("administrator");
        verify(ledger, never()).post(any());
    }

    @Test
    void anAdminCannotFundAnAccountTheyOwn() throws Exception {
        stub("ACTIVE");
        RequestPostProcessor adminWithCustomerClaim = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                .jwt(j -> j.subject(ADMIN_SUB).claim("customer_id", CUSTOMER));

        fund("api-fund-0009", body("5.00", "REF-0000-0009"), adminWithCustomerClaim)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("FUNDING_OWN_ACCOUNT"));
        verify(ledger, never()).post(any());
        verify(accounts, never()).applyOperation(any());
    }

    @Test
    void nonActiveTargetsAreRejectedWithoutAnyLedgerEntryOrBalanceChange() throws Exception {
        for (String state : new String[] {"PENDING", "REJECTED", "FROZEN", "CLOSED"}) {
            stub(state);
            fund("api-fund-" + state, body("5.00", "REF-" + state + "-0001"), adminJwt())
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.errorCode").value("FUNDING_TARGET_INELIGIBLE"));
        }
        verify(ledger, never()).post(any());
        verify(accounts, never()).applyOperation(any());
        assertThat(auditRepo.findByEventTypeOrderByIdAsc("FUNDING_FAILED")).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void ledgerRejectionLeavesNoCreditAndIsAuditedAsFailed() throws Exception {
        stub("ACTIVE");
        when(ledger.post(any())).thenReturn(RemoteResult.rejected("LEDGER_UNAVAILABLE_POLICY"));

        fund("api-fund-0010", body("5.00", "REF-0000-0010"), adminJwt())
                .andExpect(jsonPath("$.status").value("FAILED"));
        verify(accounts, never()).applyOperation(any());
    }

    @Test
    void creditRejectionAfterTheLedgerPostRollsTheLedgerEntryBack() throws Exception {
        stub("ACTIVE");
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.rejected("ACCOUNT_NOT_ACTIVE"));

        fund("api-fund-0011", body("5.00", "REF-0000-0011"), adminJwt())
                .andExpect(jsonPath("$.status").value("COMPENSATED"));
        verify(ledger, times(1)).post(any());
        verify(ledger, times(1)).reverse(any());   // failed attempt: no net ledger or balance effect
    }

    @Test
    void theCallerCannotChooseTheSourceOrSmuggleAnyOtherField() throws Exception {
        stub("ACTIVE");
        String ok = body("5.00", "REF-STRICT-001");
        String[] smuggled = {
                ok.replace("}", ",\"sourceAccountId\":\"" + DST + "\"}"),
                ok.replace("}", ",\"businessReference\":\"TRANSFER-smuggled-value\"}"),
                ok.replace("}", ",\"clearingAccountId\":\"" + DST + "\"}"),
                ok.replace("}", ",\"source\":{\"accountId\":\"" + DST + "\"}}"),
                ok.replace("}", ",\"balance\":999999}"),
                ok.replace("}", ",\"TARGETACCOUNTID\":\"" + DST + "\"}")};

        int i = 0;
        for (String json : smuggled) {
            String body = fund("api-strict-" + (i++) + "0001", json, adminJwt())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("smuggled-value").doesNotContain("sourceAccountId")
                    .doesNotContain("businessReference");
        }
        verify(accounts, never()).applyOperation(any());
        verify(ledger, never()).post(any());
        assertThat(transferRepo.count()).isZero();
    }

    @Test
    void malformedAndWrongShapedJsonUseTheEstablishedErrorFormat() throws Exception {
        stub("ACTIVE");
        for (String json : new String[] {"{not json", "[]", "\"text\"", "{\"targetAccountId\":\"nope\","
                + "\"amount\":1,\"currency\":\"INR\",\"fundingReference\":\"REF-STRICT-002\",\"reason\":\"r\"}"}) {
            fund("api-strict-shape-01", json, adminJwt()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        verify(ledger, never()).post(any());
    }

    @Test
    void aValidRequestStillDeserializesAndTheFixedContraSourceCannotBeOverridden() throws Exception {
        stub("ACTIVE");

        fund("api-strict-valid-1", body("12.50", "REF-STRICT-003"), adminJwt())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(12.50))
                .andExpect(jsonPath("$.sourceAccountId").value(FundingSource.CONTRA_ACCOUNT.toString()));
    }

    @Test
    void invalidBodiesAreRejected() throws Exception {
        stub("ACTIVE");
        String noReason = "{\"targetAccountId\":\"" + SRC + "\",\"amount\":5.00,\"currency\":\"INR\","
                + "\"fundingReference\":\"REF-0000-0012\"}";
        String blankReason = noReason.replace("}", ",\"reason\":\"   \"}");
        String withSource = body("5.00", "REF-0000-0013").replace("}", ",\"sourceAccountId\":\"" + DST + "\"}");

        fund("api-fund-0012", noReason, adminJwt()).andExpect(status().isBadRequest());
        fund("api-fund-0013", blankReason, adminJwt()).andExpect(status().isBadRequest());
        fund("api-fund-0014", withSource, adminJwt()).andExpect(status().isBadRequest());
        fund("api-fund-0015", body("0", "REF-0000-0015"), adminJwt()).andExpect(status().isBadRequest());
        fund("api-fund-0016", body("-1", "REF-0000-0016"), adminJwt()).andExpect(status().isBadRequest());
        fund("api-fund-0017", body("5.00", "short"), adminJwt()).andExpect(status().isBadRequest());
        fund("api-fund-0018", body("5.001", "REF-0000-0018"), adminJwt()).andExpect(status().isBadRequest());
        fund("api-fund-0019", body("100000.01", "REF-0000-0019"), adminJwt())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AMOUNT_EXCEEDS_LIMIT"));
        fund("api-fund-0020", body("5.00", "REF-0000-0020").replace("INR", "USD"), adminJwt())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
        mockMvc.perform(post("/api/v1/admin/funding").with(adminJwt()).contentType(MediaType.APPLICATION_JSON)
                .content(body("5.00", "REF-0000-0021"))).andExpect(status().isBadRequest());   // no Idempotency-Key
        verify(accounts, never()).applyOperation(any());
        verify(ledger, never()).post(any());
    }
}
