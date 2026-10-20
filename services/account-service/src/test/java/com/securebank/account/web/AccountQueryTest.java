package com.securebank.account.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.account.domain.Account;
import com.securebank.account.support.AbstractIntegrationTest;
import com.securebank.account.support.TestJwts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Retrieval, listing, pagination, status and balance endpoints, including ownership rules. */
class AccountQueryTest extends AbstractIntegrationTest {

    // ------------------------------------------------------------------ by id

    @Test
    void ownerAndAdminCanReadAnAccountButOtherCustomersGetNotFound() throws Exception {
        UUID owner = UUID.randomUUID();
        Account account = insertAccount(owner);
        String url = "/api/v1/accounts/" + account.getId();

        mockMvc.perform(get(url).with(TestJwts.customer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(account.getId().toString()))
                .andExpect(jsonPath("$.accountNumber").value(account.getAccountNumber()))
                .andExpect(content().string(containsString("\"availableBalance\":0.00")));

        mockMvc.perform(get(url).with(TestJwts.admin())).andExpect(status().isOk());

        mockMvc.perform(get(url).with(TestJwts.customer(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        mockMvc.perform(get(url).with(TestJwts.customerWithoutCustomerId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownIdIsNotFoundAndMalformedIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID()).with(TestJwts.admin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/accounts/not-a-uuid").with(TestJwts.admin()))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ by account number

    @Test
    void lookupByAccountNumberFollowsTheSameOwnershipRules() throws Exception {
        UUID owner = UUID.randomUUID();
        Account account = insertAccount(owner);
        String url = "/api/v1/account-numbers/" + account.getAccountNumber();

        mockMvc.perform(get(url).with(TestJwts.customer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(account.getId().toString()));
        mockMvc.perform(get(url).with(TestJwts.admin())).andExpect(status().isOk());
        mockMvc.perform(get(url).with(TestJwts.customer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void lookupByUnknownOrMalformedAccountNumber() throws Exception {
        mockMvc.perform(get("/api/v1/account-numbers/9999999999999").with(TestJwts.admin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/account-numbers/abc").with(TestJwts.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------------ customer listing + pagination

    @Test
    void customerListingIsPaginatedAndContainsOnlyOwnAccounts() throws Exception {
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            insertAccount(owner);
        }
        insertAccount(UUID.randomUUID());

        mockMvc.perform(get("/api/v1/accounts").param("customerId", owner.toString())
                        .param("page", "0").param("size", "2").with(TestJwts.customer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[?(@.customerId != '" + owner + "')]").isEmpty());

        mockMvc.perform(get("/api/v1/accounts").param("customerId", owner.toString())
                        .param("page", "1").param("size", "2").with(TestJwts.customer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        mockMvc.perform(get("/api/v1/accounts").param("customerId", owner.toString())
                        .param("page", "5").param("size", "2").with(TestJwts.customer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void customerCannotListAnotherCustomersAccountsButAdminCan() throws Exception {
        UUID owner = UUID.randomUUID();
        insertAccount(owner);

        mockMvc.perform(get("/api/v1/accounts").param("customerId", owner.toString())
                        .with(TestJwts.customer(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/accounts").param("customerId", owner.toString())
                        .with(TestJwts.customerWithoutCustomerId()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/accounts").param("customerId", owner.toString()).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void invalidPaginationAndFilterParametersAreRejected() throws Exception {
        UUID owner = UUID.randomUUID();
        String id = owner.toString();
        mockMvc.perform(get("/api/v1/accounts").param("customerId", id).param("size", "101")
                        .with(TestJwts.customer(owner))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/accounts").param("customerId", id).param("size", "0")
                        .with(TestJwts.customer(owner))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/accounts").param("customerId", id).param("page", "-1")
                        .with(TestJwts.customer(owner))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/accounts").param("customerId", id).param("status", "BOGUS")
                        .with(TestJwts.customer(owner))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/accounts").with(TestJwts.customer(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerListingCanFilterByStatus() throws Exception {
        UUID owner = UUID.randomUUID();
        insertAccount(owner);
        Account frozen = insertAccount(owner);
        frozen.freeze("test", "admin", Instant.now());
        accountRepository.saveAndFlush(frozen);

        mockMvc.perform(get("/api/v1/accounts").param("customerId", owner.toString())
                        .param("status", "FROZEN").with(TestJwts.customer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("FROZEN"));
    }

    // ------------------------------------------------------------------ admin search

    @Test
    void adminSearchSupportsFiltersAndPagination() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertAccount(first);
        insertAccount(first);
        Account frozen = insertAccount(second);
        frozen.freeze("test", "admin", Instant.now());
        accountRepository.saveAndFlush(frozen);

        mockMvc.perform(get("/api/v1/admin/accounts").param("size", "2").with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/v1/admin/accounts").param("customerId", first.toString()).with(TestJwts.admin()))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/admin/accounts").param("status", "FROZEN").with(TestJwts.admin()))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/admin/accounts").param("accountType", "SAVINGS").with(TestJwts.admin()))
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(get("/api/v1/admin/accounts").param("accountType", "CURRENT").with(TestJwts.admin()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void customersCannotUseTheAdminSearch() throws Exception {
        mockMvc.perform(get("/api/v1/admin/accounts").with(TestJwts.customer(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // ------------------------------------------------------------------ status and balance

    @Test
    void statusEndpointReturnsTheCurrentStatus() throws Exception {
        UUID owner = UUID.randomUUID();
        Account account = insertAccount(owner);

        mockMvc.perform(get("/api/v1/accounts/" + account.getId() + "/status").with(TestJwts.customer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(account.getId().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/accounts/" + account.getId() + "/status")
                        .with(TestJwts.customer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void balanceEndpointReturnsExactTwoDecimalAmounts() throws Exception {
        UUID owner = UUID.randomUUID();
        Account account = insertAccount(owner);
        String url = "/api/v1/accounts/" + account.getId() + "/balance";

        mockMvc.perform(get(url).with(TestJwts.customer(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"availableBalance\":0.00")))
                .andExpect(jsonPath("$.currency").value("INR"));

        account.credit(new BigDecimal("125.50"), Instant.now());
        accountRepository.saveAndFlush(account);

        mockMvc.perform(get(url).with(TestJwts.customer(owner)))
                .andExpect(content().string(containsString("\"availableBalance\":125.50")));
        mockMvc.perform(get(url).with(TestJwts.customer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }
}
