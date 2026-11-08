package com.securebank.transaction.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.transaction.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Funding is OFF unless a clearing account is explicitly configured: nothing is funded, nothing is guessed. */
class FundingDisabledApiTest extends AbstractIntegrationTest {

    @Test
    void fundingIsUnavailableWhenNoClearingAccountIsConfigured() throws Exception {
        mockMvc.perform(post("/api/v1/transfers/funding")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                .jwt(j -> j.subject("user-" + CUSTOMER).claim("customer_id", CUSTOMER)))
                        .header("Idempotency-Key", "api-fund-off-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationAccountId\":\"" + SRC + "\",\"amount\":5.00,\"currency\":\"INR\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FUNDING_NOT_AVAILABLE"));

        verify(accounts, never()).applyOperation(any());
        verify(ledger, never()).post(any());
        org.assertj.core.api.Assertions.assertThat(transferRepo.count()).isZero();
    }
}
