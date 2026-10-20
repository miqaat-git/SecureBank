package com.securebank.account.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.account.domain.Account;
import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import com.securebank.account.support.AbstractIntegrationTest;
import com.securebank.account.support.TestJwts;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Account creation: validation, Customer Service integration, ownership and financial safety. */
class AccountCreationTest extends AbstractIntegrationTest {

    private static String body(UUID customerId, String accountType) {
        return "{\"customerId\":\"" + customerId + "\",\"accountType\":\"" + accountType + "\"}";
    }

    @Test
    void adminOpensAccountWithZeroBalanceAndNoCustomerProfileData() throws Exception {
        UUID customer = UUID.randomUUID();
        givenCustomer(customer, "ACTIVE");

        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body(customer, "SAVINGS")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/accounts/")))
                .andExpect(jsonPath("$.customerId").value(customer.toString()))
                .andExpect(jsonPath("$.accountType").value("SAVINGS"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.accountNumber").value(matchesPattern("\\d{13}")))
                .andExpect(jsonPath("$.fullName").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(content().string(containsString("\"availableBalance\":0.00")));

        assertThat(accountRepository.count()).isEqualTo(1);
        Account stored = accountRepository.findAll().get(0);
        assertThat(stored.getAvailableBalance()).isEqualByComparingTo("0.00");
        assertThat(stored.getAccountNumber()).doesNotContain(stored.getId().toString());
    }

    @Test
    void customerMayOpenAnAccountForThemselves() throws Exception {
        UUID customer = UUID.randomUUID();
        givenCustomer(customer, "ACTIVE");

        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(body(customer, "CURRENT")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountType").value("CURRENT"));
    }

    @Test
    void customerCannotOpenAnAccountForSomeoneElse() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.customer(other))
                        .contentType(MediaType.APPLICATION_JSON).content(body(owner, "SAVINGS")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(customerClient);
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void clientCannotSupplyABalanceOrAnyOtherUnknownField() throws Exception {
        UUID customer = UUID.randomUUID();
        givenCustomer(customer, "ACTIVE");
        String withBalance = "{\"customerId\":\"" + customer
                + "\",\"accountType\":\"SAVINGS\",\"availableBalance\":1000000.00}";
        String withBalanceAlias = "{\"customerId\":\"" + customer
                + "\",\"accountType\":\"SAVINGS\",\"balance\":5}";

        for (String json : new String[] {withBalance, withBalanceAlias}) {
            mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin())
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest());
        }
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void missingRequiredFieldsReturnFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='customerId')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='accountType')]").exists());
    }

    @Test
    void malformedJsonAndUnknownAccountTypeAreBadRequests() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), "PLATINUM")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyInrIsSupported() throws Exception {
        UUID customer = UUID.randomUUID();
        givenCustomer(customer, "ACTIVE");

        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"accountType\":\"SAVINGS\",\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));

        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"accountType\":\"SAVINGS\",\"currency\":\"inr\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"accountType\":\"SAVINGS\",\"currency\":\"INR\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void unknownCustomerIsRejected() throws Exception {
        UUID customer = UUID.randomUUID(); // customerClient mock returns Optional.empty() by default

        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body(customer, "SAVINGS")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void ineligibleCustomerIsRejected() throws Exception {
        UUID customer = UUID.randomUUID();
        givenCustomer(customer, "SUSPENDED");

        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body(customer, "SAVINGS")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_ELIGIBLE"));
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void customerServiceOutageIsNeverTreatedAsExistingCustomer() throws Exception {
        UUID customer = UUID.randomUUID();
        when(customerClient.findCustomer(any())).thenThrow(
                new ApiException(ErrorCode.CUSTOMER_SERVICE_UNAVAILABLE, "The Customer Service is unavailable."));

        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body(customer, "SAVINGS")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CUSTOMER_SERVICE_UNAVAILABLE"));
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void numberOfOpenAccountsPerCustomerIsLimited() throws Exception {
        UUID customer = UUID.randomUUID();
        givenCustomer(customer, "ACTIVE");
        for (int i = 0; i < 5; i++) {
            insertAccount(customer);
        }

        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body(customer, "SAVINGS")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LIMIT_EXCEEDED"));
        assertThat(accountRepository.count()).isEqualTo(5);
    }

    @Test
    void createRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), "SAVINGS")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }
}
