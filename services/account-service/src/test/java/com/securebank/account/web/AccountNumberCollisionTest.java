package com.securebank.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.account.domain.Account;
import com.securebank.account.service.AccountNumberGenerator;
import com.securebank.account.support.AbstractIntegrationTest;
import com.securebank.account.support.TestJwts;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Unique account numbers: application pre-check, retry on collision, and give-up behaviour. */
class AccountNumberCollisionTest extends AbstractIntegrationTest {

    @MockitoBean
    private AccountNumberGenerator numberGenerator;

    private String body(UUID customer) {
        return "{\"customerId\":\"" + customer + "\",\"accountType\":\"SAVINGS\"}";
    }

    @Test
    void retriesWithAnotherNumberWhenTheFirstCandidateIsAlreadyTaken() throws Exception {
        Account existing = insertAccount(UUID.randomUUID());
        UUID customer = UUID.randomUUID();
        givenCustomer(customer, "ACTIVE");
        when(numberGenerator.generate()).thenReturn(existing.getAccountNumber(), "3000000000001");

        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body(customer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumber").value("3000000000001"));

        verify(numberGenerator, times(2)).generate();
        assertThat(accountRepository.count()).isEqualTo(2);
    }

    @Test
    void givesUpWithServiceUnavailableWhenNoUniqueNumberCanBeFound() throws Exception {
        Account existing = insertAccount(UUID.randomUUID());
        UUID customer = UUID.randomUUID();
        givenCustomer(customer, "ACTIVE");
        when(numberGenerator.generate()).thenReturn(existing.getAccountNumber());

        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body(customer)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NUMBER_UNAVAILABLE"));

        assertThat(accountRepository.count()).isEqualTo(1);
    }
}
