package com.securebank.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import com.securebank.account.support.AbstractIntegrationTest;
import com.securebank.account.support.TestJwts;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Customer application (D6; administrators cannot apply): ownership is bound to the validated token's customer_id, eligibility is checked
 * through the Customer client (which uses this service's own credential), and failures map to stable API errors.
 */
class CustomerSelfOpeningTest extends AbstractIntegrationTest {

    private ResultActions open(RequestPostProcessor who, UUID bodyCustomerId) throws Exception {
        return mockMvc.perform(post("/api/v1/accounts").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + bodyCustomerId + "\",\"accountType\":\"SAVINGS\"}"));
    }

    @Test
    void customerOpensOwnAccountAndEligibilityIsCheckedForTheTokenIdentity() throws Exception {
        UUID me = UUID.randomUUID();
        givenCustomer(me, "ACTIVE");

        open(TestJwts.customer(me), me).andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(me.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(customerClient).findCustomer(me);
        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(accountRepository.findAll().get(0).getCustomerId()).isEqualTo(me);
    }

    @Test
    void aCustomerCannotOpenForAnotherCustomerByTamperingWithTheBody() throws Exception {
        UUID me = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        givenCustomer(me, "ACTIVE");
        givenCustomer(victim, "ACTIVE");

        open(TestJwts.customer(me), victim).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        verifyNoInteractions(customerClient);
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void aTokenWithoutACustomerIdentityCannotOpenAnything() throws Exception {
        UUID target = UUID.randomUUID();
        givenCustomer(target, "ACTIVE");

        open(TestJwts.customerWithoutCustomerId(), target).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(customerClient);
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void unknownCustomerIsRefusedWithoutCreatingAnAccount() throws Exception {
        UUID me = UUID.randomUUID();
        when(customerClient.findCustomer(me)).thenReturn(Optional.empty());

        open(TestJwts.customer(me), me).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void ineligibleCustomersAreRefused() throws Exception {
        for (String statusName : new String[] {"SUSPENDED", "CLOSED", "unknown"}) {
            UUID me = UUID.randomUUID();
            givenCustomer(me, statusName);

            open(TestJwts.customer(me), me).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_ELIGIBLE"));
        }
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void customerServiceTroubleIsASafe503AndNeverACreatedAccount() throws Exception {
        UUID me = UUID.randomUUID();
        when(customerClient.findCustomer(any())).thenThrow(
                new ApiException(ErrorCode.CUSTOMER_SERVICE_UNAVAILABLE, "The Customer Service is unavailable."));

        open(TestJwts.customer(me), me).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CUSTOMER_SERVICE_UNAVAILABLE"));
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void machineAndAnonymousCallersCannotOpenAccounts() throws Exception {
        UUID target = UUID.randomUUID();
        givenCustomer(target, "ACTIVE");

        open(TestJwts.transactionService(), target).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + target + "\",\"accountType\":\"SAVINGS\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(customerClient);
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void anAdministratorCannotOpenAccountsOnBehalfOfACustomer() throws Exception {
        UUID target = UUID.randomUUID();
        givenCustomer(target, "ACTIVE");

        open(TestJwts.admin(), target).andExpect(status().isForbidden());
        verifyNoInteractions(customerClient);
        assertThat(accountRepository.count()).isZero();
    }
}
