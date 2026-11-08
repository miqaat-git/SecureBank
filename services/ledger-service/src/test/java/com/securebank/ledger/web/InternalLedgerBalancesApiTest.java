package com.securebank.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.ledger.support.AbstractLedgerTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/** Read-only reconciliation totals: debit/credit semantics, reversal netting, authorization, no writes. */
class InternalLedgerBalancesApiTest extends AbstractLedgerTest {

    @Autowired MockMvc mvc;

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    private static JwtRequestPostProcessor as(String... roles) {
        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[roles.length];
        for (int i = 0; i < roles.length; i++) {
            authorities[i] = new SimpleGrantedAuthority("ROLE_" + roles[i]);
        }
        return jwt().jwt(t -> t.subject("transaction-service-account")).authorities(authorities);
    }

    private void transfer(String reference, UUID debit, UUID credit, String amount) throws Exception {
        mvc.perform(post("/internal/journal-entries").with(as("LEDGER_WRITER")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"businessReference":"%s","idempotencyKey":"%s","description":"t","currency":"INR","entries":[
                         {"accountId":"%s","direction":"DEBIT","amount":%s},
                         {"accountId":"%s","direction":"CREDIT","amount":%s}]}
                        """.formatted(reference, UUID.randomUUID(), debit, amount, credit, amount)))
                .andExpect(status().isCreated());
    }

    private void reverse(String reference, String reversalReference) throws Exception {
        mvc.perform(post("/internal/journal-entries/" + reference + "/reversals").with(as("LEDGER_REVERSER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reversalBusinessReference\":\"" + reversalReference + "\",\"idempotencyKey\":\""
                        + UUID.randomUUID() + "\",\"reason\":\"compensation\"}"))
                .andExpect(status().isCreated());
    }

    private String query(UUID... ids) {
        StringBuilder sb = new StringBuilder();
        for (UUID id : ids) {
            sb.append(sb.length() == 0 ? "" : ",").append(id);
        }
        return sb.toString();
    }

    @Test
    void creditsIncreaseAndDebitsDecreaseTheLiabilityView() throws Exception {
        transfer("T-1", b, a, "100.00");     // a credited 100, b debited 100
        transfer("T-2", a, b, "30.00");      // a debited 30

        mvc.perform(get("/internal/ledger-balances").param("accountIds", query(a)).with(as("LEDGER_READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.balances.length()").value(1))
                .andExpect(jsonPath("$.balances[0].accountId").value(a.toString().toUpperCase()))
                .andExpect(jsonPath("$.balances[0].currency").value("INR"))
                .andExpect(jsonPath("$.balances[0].creditMinusDebit").value(70.0))
                .andExpect(jsonPath("$.balances[0].lineCount").value(2));
    }

    @Test
    void aReversalNetsAgainstItsOriginalWithoutDoubleCounting() throws Exception {
        transfer("T-3", b, a, "50.00");
        reverse("T-3", "REV-T-3");

        mvc.perform(get("/internal/ledger-balances").param("accountIds", query(a, b)).with(as("LEDGER_READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balances.length()").value(2))
                .andExpect(jsonPath("$.balances[0].creditMinusDebit").value(0.0))
                .andExpect(jsonPath("$.balances[1].creditMinusDebit").value(0.0))
                .andExpect(jsonPath("$.balances[0].lineCount").value(2));
    }

    @Test
    void accountsWithoutPostedLinesAreAbsentAndTheCallWritesNothing() throws Exception {
        long entries = countEntries();
        long lines = countLines();
        mvc.perform(get("/internal/ledger-balances").param("accountIds", query(UUID.randomUUID()))
                        .with(as("LEDGER_READER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balances.length()").value(0));
        assertThat(countEntries()).isEqualTo(entries);
        assertThat(countLines()).isEqualTo(lines);
    }

    @Test
    void invalidAndOversizedRequestsAreRejected() throws Exception {
        mvc.perform(get("/internal/ledger-balances").param("accountIds", "not-a-uuid").with(as("LEDGER_READER")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/ledger-balances").with(as("LEDGER_READER"))).andExpect(status().isBadRequest());
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            many.append(i == 0 ? "" : ",").append(UUID.randomUUID());
        }
        mvc.perform(get("/internal/ledger-balances").param("accountIds", many.toString()).with(as("LEDGER_READER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyMachineLedgerRolesMayReadAndThereIsNoWriteRoute() throws Exception {
        mvc.perform(get("/internal/ledger-balances").param("accountIds", query(a))).andExpect(status().isUnauthorized());
        for (String human : new String[] {"CUSTOMER", "ADMIN"}) {
            mvc.perform(get("/internal/ledger-balances").param("accountIds", query(a)).with(as(human)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/internal/ledger-balances").with(as("LEDGER_WRITER")).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
    }
}
