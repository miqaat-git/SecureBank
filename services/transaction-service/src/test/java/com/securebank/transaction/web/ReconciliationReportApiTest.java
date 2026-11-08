package com.securebank.transaction.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.transaction.AbstractIntegrationTest;
import com.securebank.transaction.client.AccountBalancePage;
import com.securebank.transaction.client.AccountBalanceReader;
import com.securebank.transaction.client.AccountBalanceRow;
import com.securebank.transaction.client.LedgerBalanceReader;
import com.securebank.transaction.client.LedgerBalanceRow;
import com.securebank.transaction.client.LedgerBalances;
import com.securebank.transaction.client.RemoteCallException;
import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Read-only Account-to-Ledger reconciliation view: results, reversals, outages, paging, authorization. */
class ReconciliationReportApiTest extends AbstractIntegrationTest {

    private static final String PATH = "/api/v1/admin/reconciliation/account-balances";
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    @MockitoBean AccountBalanceReader accountReader;
    @MockitoBean LedgerBalanceReader ledgerReader;

    private static RequestPostProcessor adminJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")).jwt(j -> j.subject("admin-1"));
    }

    private static AccountBalanceRow acct(UUID id, String balance) {
        return new AccountBalanceRow(id, "INR", "ACTIVE", new BigDecimal(balance), 1L, T);
    }

    private static LedgerBalanceRow ledgerRow(UUID id, String credits, String debits, long lines) {
        BigDecimal c = new BigDecimal(credits);
        BigDecimal d = new BigDecimal(debits);
        return new LedgerBalanceRow(id.toString().toUpperCase(), "INR", d, c, c.subtract(d), lines);
    }

    private void accountsAre(AccountBalanceRow... rows) {
        doReturn(new AccountBalancePage(List.of(rows), 0, 50, rows.length, 1))
                .when(accountReader).list(any(), any(), any(), anyInt(), anyInt());
    }

    private void ledgerIs(LedgerBalanceRow... rows) {
        when(ledgerReader.balances(any())).thenReturn(new LedgerBalances(T, List.of(rows)));
    }

    @BeforeEach
    void defaults() {
        accountsAre();
    }

    @Test
    void matchingMismatchingAndMissingLedgerAccountsAreClassifiedAndTraceable() throws Exception {
        UUID ok = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        UUID zero = UUID.randomUUID();
        accountsAre(acct(ok, "70.00"), acct(bad, "80.00"), acct(empty, "25.00"), acct(zero, "0.00"));
        ledgerIs(ledgerRow(ok, "100.00", "30.00", 2), ledgerRow(bad, "100.00", "30.00", 2));

        mockMvc.perform(get(PATH).with(adminJwt()).header("X-Correlation-Id", "corr-recon-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.ledgerReadAt").exists())
                .andExpect(jsonPath("$.correlationId").value("corr-recon-1"))
                .andExpect(jsonPath("$.summary.matches").value(2))
                .andExpect(jsonPath("$.summary.discrepancies").value(2))
                .andExpect(jsonPath("$.summary.incomplete").value(0))
                .andExpect(jsonPath("$.rows[0].result").value("MATCH"))
                .andExpect(jsonPath("$.rows[0].ledgerBalance").value(70.0))
                .andExpect(jsonPath("$.rows[0].difference").value(0.0))
                .andExpect(jsonPath("$.rows[1].result").value("DISCREPANCY"))
                .andExpect(jsonPath("$.rows[1].reasonCode").value("BALANCE_MISMATCH"))
                .andExpect(jsonPath("$.rows[1].difference").value(10.0))
                .andExpect(jsonPath("$.rows[2].result").value("DISCREPANCY"))
                .andExpect(jsonPath("$.rows[2].reasonCode").value("NO_LEDGER_ENTRIES"))
                .andExpect(jsonPath("$.rows[3].result").value("MATCH"));   // no ledger lines and zero balance
    }

    @Test
    void aReversedPostingNetsToZeroAndAnAccountThatStillShowsTheCreditIsADiscrepancy() throws Exception {
        UUID compensated = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        // ledger lines of a posting and its reversal: credits 50 and debits 50 on the same account
        accountsAre(acct(compensated, "0.00"), acct(stale, "50.00"));
        ledgerIs(ledgerRow(compensated, "50.00", "50.00", 2), ledgerRow(stale, "50.00", "50.00", 2));

        mockMvc.perform(get(PATH).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].result").value("MATCH"))
                .andExpect(jsonPath("$.rows[0].ledgerBalance").value(0.0))
                .andExpect(jsonPath("$.rows[1].result").value("DISCREPANCY"))
                .andExpect(jsonPath("$.rows[1].difference").value(50.0));
    }

    @Test
    void aDifferenceExplainedByAnUnfinishedTransferIsIncompleteNotConfirmed() throws Exception {
        UUID inFlight = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        accountsAre(acct(inFlight, "10.00"));
        ledgerIs(ledgerRow(inFlight, "60.00", "0.00", 1));
        transferRepo.save(Transfer.initiate(UUID.randomUUID(), "recon-key-0001", "fp-recon", other, inFlight,
                new BigDecimal("50.00"), "INR", "user-1", CUSTOMER, Instant.now()));
        Transfer t = transferRepo.findAll().get(0);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.INITIATED);

        mockMvc.perform(get(PATH).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].result").value("INCOMPLETE"))
                .andExpect(jsonPath("$.rows[0].reasonCode").value("UNFINISHED_TRANSFERS"))
                .andExpect(jsonPath("$.summary.incomplete").value(1))
                .andExpect(jsonPath("$.summary.discrepancies").value(0));
    }

    @Test
    void linesInAnotherCurrencyMakeTheComparisonIncompleteNeverAMatch() throws Exception {
        UUID id = UUID.randomUUID();
        accountsAre(acct(id, "0.00"));
        when(ledgerReader.balances(any())).thenReturn(new LedgerBalances(T, List.of(new LedgerBalanceRow(
                id.toString().toUpperCase(), "USD", BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN, 1))));

        mockMvc.perform(get(PATH).with(adminJwt()))
                .andExpect(jsonPath("$.rows[0].result").value("INCOMPLETE"))
                .andExpect(jsonPath("$.rows[0].reasonCode").value("LEDGER_HAS_OTHER_CURRENCY_LINES"));
    }

    @Test
    void anUnavailableAccountOrLedgerServiceIsAClearErrorNotAPartialReport() throws Exception {
        UUID id = UUID.randomUUID();
        when(accountReader.list(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new RemoteCallException("ACCOUNT_SERVICE_UNREACHABLE"));
        mockMvc.perform(get(PATH).with(adminJwt()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SERVICE_UNAVAILABLE"));
        verifyNoInteractions(ledgerReader);

        accountsAre(acct(id, "1.00"));
        when(ledgerReader.balances(any())).thenThrow(new RemoteCallException("LEDGER_SERVICE_HTTP_503"));
        mockMvc.perform(get(PATH).with(adminJwt()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LEDGER_SERVICE_UNAVAILABLE"));
    }

    @Test
    void paginationFiltersAndResultFilterAreAppliedAndBounded() throws Exception {
        UUID ok = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        when(accountReader.list(eq("ACTIVE"), eq("INR"), any(), eq(2), eq(2)))
                .thenReturn(new AccountBalancePage(List.of(acct(ok, "5.00"), acct(bad, "9.00")), 2, 2, 12, 6));
        ledgerIs(ledgerRow(ok, "5.00", "0.00", 1), ledgerRow(bad, "5.00", "0.00", 1));

        mockMvc.perform(get(PATH).param("status", "active").param("currency", "inr").param("page", "2")
                        .param("size", "2").param("result", "DISCREPANCY").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.totalAccounts").value(12))
                .andExpect(jsonPath("$.totalPages").value(6))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].accountId").value(bad.toString()))
                .andExpect(jsonPath("$.summary.matches").value(1));

        mockMvc.perform(get(PATH).param("size", "101").with(adminJwt())).andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH).param("page", "-1").with(adminJwt())).andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH).param("result", "BOGUS").with(adminJwt())).andExpect(status().isBadRequest());
    }

    @Test
    void onlyAdministratorsMayReadAndNothingIsEverWritten() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                .jwt(j -> j.subject("u").claim("customer_id", CUSTOMER)))).andExpect(status().isForbidden());
        mockMvc.perform(post(PATH).with(adminJwt()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());

        UUID id = UUID.randomUUID();
        accountsAre(acct(id, "3.00"));
        ledgerIs(ledgerRow(id, "1.00", "0.00", 1));
        long transfers = transferRepo.count();
        mockMvc.perform(get(PATH).with(adminJwt())).andExpect(status().isOk());

        assertThat(transferRepo.count()).isEqualTo(transfers);
        verify(accounts, never()).applyOperation(any());
        verify(ledger, never()).post(any());
        verify(ledger, never()).reverse(any());
    }
}
