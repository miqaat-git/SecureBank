package com.securebank.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountStatus;
import com.securebank.account.support.AbstractIntegrationTest;
import com.securebank.account.support.TestJwts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Freeze / unfreeze, closure rules, and the guarantee that no public endpoint can write a balance. */
class AccountLifecycleTest extends AbstractIntegrationTest {

    private static final String REASON = "{\"reason\":\"Suspicious activity review\"}";

    private AccountStatus statusOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getStatus();
    }

    // ------------------------------------------------------------------ freeze / unfreeze

    @Test
    void adminFreezesAndUnfreezesAnAccount() throws Exception {
        Account account = insertAccount(UUID.randomUUID());
        String base = "/api/v1/admin/accounts/" + account.getId();

        mockMvc.perform(post(base + "/freeze").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        assertThat(statusOf(account)).isEqualTo(AccountStatus.FROZEN);
        Account frozen = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(frozen.getStatusReason()).isEqualTo("Suspicious activity review");
        assertThat(frozen.getStatusChangedBy()).isEqualTo("admin-user-1");

        mockMvc.perform(post(base + "/unfreeze").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Review complete\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(statusOf(account)).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void invalidTransitionsAreConflicts() throws Exception {
        Account account = insertAccount(UUID.randomUUID());
        String base = "/api/v1/admin/accounts/" + account.getId();

        mockMvc.perform(post(base + "/unfreeze").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_STATE"));

        mockMvc.perform(post(base + "/freeze").with(TestJwts.admin())
                .contentType(MediaType.APPLICATION_JSON).content(REASON)).andExpect(status().isOk());
        mockMvc.perform(post(base + "/freeze").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_STATE"));
    }

    @Test
    void freezeRequiresAReasonAndAnExistingAccount() throws Exception {
        Account account = insertAccount(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/admin/accounts/" + account.getId() + "/freeze").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/admin/accounts/" + account.getId() + "/freeze").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/accounts/" + UUID.randomUUID() + "/freeze").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isNotFound());
        assertThat(statusOf(account)).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void onlyAdminsMayFreezeOrUnfreeze() throws Exception {
        UUID owner = UUID.randomUUID();
        Account account = insertAccount(owner);
        String base = "/api/v1/admin/accounts/" + account.getId();

        mockMvc.perform(post(base + "/freeze").with(TestJwts.customer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(base + "/freeze").with(TestJwts.transactionService())
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(base + "/freeze").contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isUnauthorized());
        assertThat(statusOf(account)).isEqualTo(AccountStatus.ACTIVE);
    }

    // ------------------------------------------------------------------ closure

    @Test
    void ownerClosesAZeroBalanceAccount() throws Exception {
        UUID owner = UUID.randomUUID();
        Account account = insertAccount(owner);

        mockMvc.perform(post("/api/v1/accounts/" + account.getId() + "/close").with(TestJwts.customer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        Account closed = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
    }

    @Test
    void adminCanCloseAZeroBalanceAccount() throws Exception {
        Account account = insertAccount(UUID.randomUUID());
        mockMvc.perform(post("/api/v1/accounts/" + account.getId() + "/close").with(TestJwts.admin()))
                .andExpect(status().isOk());
    }

    @Test
    void accountWithBalanceCannotBeClosed() throws Exception {
        UUID owner = UUID.randomUUID();
        Account account = insertAccount(owner);
        account.credit(new BigDecimal("0.01"), Instant.now());
        accountRepository.saveAndFlush(account);

        mockMvc.perform(post("/api/v1/accounts/" + account.getId() + "/close").with(TestJwts.customer(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSURE_NOT_ALLOWED"));
        assertThat(statusOf(account)).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void frozenAccountCannotBeClosed() throws Exception {
        UUID owner = UUID.randomUUID();
        Account account = insertAccount(owner);
        account.freeze("review", "admin", Instant.now());
        accountRepository.saveAndFlush(account);

        mockMvc.perform(post("/api/v1/accounts/" + account.getId() + "/close").with(TestJwts.customer(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSURE_NOT_ALLOWED"));
        assertThat(statusOf(account)).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    void closingTwiceIsAConflict() throws Exception {
        UUID owner = UUID.randomUUID();
        Account account = insertAccount(owner);
        String url = "/api/v1/accounts/" + account.getId() + "/close";

        mockMvc.perform(post(url).with(TestJwts.customer(owner))).andExpect(status().isOk());
        mockMvc.perform(post(url).with(TestJwts.customer(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_STATE"));
    }

    @Test
    void otherCustomersCannotCloseAnAccountAndLearnNothingAboutIt() throws Exception {
        Account account = insertAccount(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/accounts/" + account.getId() + "/close")
                        .with(TestJwts.customer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        assertThat(statusOf(account)).isEqualTo(AccountStatus.ACTIVE);
    }

    // ------------------------------------------------------------------ no direct balance writes

    @Test
    void noPublicEndpointCanWriteABalance() throws Exception {
        UUID owner = UUID.randomUUID();
        Account account = insertAccount(owner);
        String url = "/api/v1/accounts/" + account.getId();
        String payload = "{\"availableBalance\":999999.99}";

        for (var caller : new org.springframework.test.web.servlet.request.RequestPostProcessor[] {
                TestJwts.customer(owner), TestJwts.admin()}) {
            mockMvc.perform(put(url).with(caller).contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isMethodNotAllowed());
            mockMvc.perform(patch(url).with(caller).contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isMethodNotAllowed());
            mockMvc.perform(delete(url).with(caller)).andExpect(status().isMethodNotAllowed());
            mockMvc.perform(put(url + "/balance").with(caller).contentType(MediaType.APPLICATION_JSON)
                    .content(payload)).andExpect(status().isMethodNotAllowed());
            mockMvc.perform(post(url + "/balance").with(caller).contentType(MediaType.APPLICATION_JSON)
                    .content(payload)).andExpect(status().isMethodNotAllowed());
        }

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getAvailableBalance())
                .isEqualByComparingTo("0.00");
    }
}
