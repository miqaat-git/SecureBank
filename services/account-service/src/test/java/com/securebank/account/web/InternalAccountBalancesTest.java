package com.securebank.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.account.domain.Account;
import com.securebank.account.support.AbstractIntegrationTest;
import com.securebank.account.support.TestJwts;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Read-only balance listing used by the reconciliation view. */
class InternalAccountBalancesTest extends AbstractIntegrationTest {

    @Test
    void theMachineRoleSeesBalancesWithoutCustomerData() throws Exception {
        Account account = insertAccount(UUID.randomUUID());

        mockMvc.perform(get("/internal/account-balances").param("accountId", account.getId().toString())
                        .with(TestJwts.transactionService()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].accountId").value(account.getId().toString()))
                .andExpect(jsonPath("$.content[0].currency").value("INR"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].availableBalance").exists())
                .andExpect(jsonPath("$.content[0].asOf").exists())
                .andExpect(jsonPath("$.content[0].customerId").doesNotExist())
                .andExpect(jsonPath("$.content[0].accountNumber").doesNotExist());
    }

    @Test
    void filtersAndPaginationAreBoundedAndTheCallModifiesNothing() throws Exception {
        Account a = insertAccount(UUID.randomUUID());
        insertAccount(UUID.randomUUID());
        BigDecimal before = accountRepository.findById(a.getId()).orElseThrow().getAvailableBalance();
        long count = accountRepository.count();

        mockMvc.perform(get("/internal/account-balances").param("size", "1").with(TestJwts.transactionService()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/internal/account-balances").param("status", "FROZEN").with(TestJwts.transactionService()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/internal/account-balances").param("size", "101").with(TestJwts.transactionService()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/internal/account-balances").param("page", "-1").with(TestJwts.transactionService()))
                .andExpect(status().isBadRequest());

        assertThat(accountRepository.count()).isEqualTo(count);
        assertThat(accountRepository.findById(a.getId()).orElseThrow().getAvailableBalance())
                .isEqualByComparingTo(before);
    }

    @Test
    void humanTokensAndAnonymousCallersAreRefusedAndThereIsNoWriteRoute() throws Exception {
        mockMvc.perform(get("/internal/account-balances")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/account-balances").with(TestJwts.admin())).andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/account-balances").with(TestJwts.customer(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/internal/account-balances").with(TestJwts.transactionService())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
    }
}
