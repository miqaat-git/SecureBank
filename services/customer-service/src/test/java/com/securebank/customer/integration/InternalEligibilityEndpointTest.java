package com.securebank.customer.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.customer.entity.Customer;
import com.securebank.customer.repository.CustomerRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The Account Service's eligibility lookup: dedicated role only, minimal payload, no cross-over to the public API. */
@SpringBootTest
@AutoConfigureMockMvc
class InternalEligibilityEndpointTest {

    @Autowired MockMvc mockMvc;
    @Autowired CustomerRepository customerRepository;

    private static RequestPostProcessor role(String authority) {
        return jwt().authorities(new SimpleGrantedAuthority(authority));
    }

    private Customer saved(Customer.Status status) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Customer c = new Customer(String.format("CUS%012d", Math.abs(UUID.randomUUID().getMostSignificantBits()) % 1_000_000_000_000L), "Elia", "Gibility",
                token + "@example.test", null);
        c.setStatus(status);
        return customerRepository.saveAndFlush(c);
    }

    @Test
    void accountServiceRoleReadsIdAndStatusOnly() throws Exception {
        Customer active = saved(Customer.Status.ACTIVE);

        mockMvc.perform(get("/internal/v1/customers/{id}/eligibility", active.getId())
                        .with(role("ROLE_ACCOUNT_SERVICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(active.getId().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.firstName").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());
    }

    @Test
    void ineligibleStatusesAreReportedNotHidden() throws Exception {
        Customer suspended = saved(Customer.Status.SUSPENDED);

        mockMvc.perform(get("/internal/v1/customers/{id}/eligibility", suspended.getId())
                        .with(role("ROLE_ACCOUNT_SERVICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    void unknownCustomerIs404() throws Exception {
        mockMvc.perform(get("/internal/v1/customers/{id}/eligibility", UUID.randomUUID())
                        .with(role("ROLE_ACCOUNT_SERVICE")))
                .andExpect(status().isNotFound());
    }

    @Test
    void everyOtherCallerIsRefused() throws Exception {
        Customer active = saved(Customer.Status.ACTIVE);

        for (String authority : new String[] {"ROLE_STAFF", "ROLE_ADMIN", "ROLE_CUSTOMER", "ROLE_TRANSACTION_SERVICE",
                "ROLE_OTHER"}) {
            mockMvc.perform(get("/internal/v1/customers/{id}/eligibility", active.getId()).with(role(authority)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/internal/v1/customers/{id}/eligibility", active.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/v1/customers/{id}/eligibility", active.getId())
                        .with(role("ROLE_ACCOUNT_SERVICE")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void theServiceRoleCannotUseTheStaffApi() throws Exception {
        Customer active = saved(Customer.Status.ACTIVE);

        mockMvc.perform(get("/api/v1/customers/{id}", active.getId()).with(role("ROLE_ACCOUNT_SERVICE")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/customers").with(role("ROLE_ACCOUNT_SERVICE")))
                .andExpect(status().isForbidden());
    }
}
