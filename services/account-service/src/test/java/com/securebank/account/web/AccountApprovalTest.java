package com.securebank.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountStatus;
import com.securebank.account.domain.AccountStatusHistory;
import com.securebank.account.domain.AccountType;
import com.securebank.account.exception.ApiException;
import com.securebank.account.support.AbstractIntegrationTest;
import com.securebank.account.support.TestJwts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Blueprint D6: application, admin-only approval/rejection with reasons, history, reapplication. */
class AccountApprovalTest extends AbstractIntegrationTest {

    private static final String REASON = "{\"reason\":\"KYC documents verified\"}";

    private UUID apply(UUID customer, String type) throws Exception {
        givenCustomer(customer, "ACTIVE");
        String json = mockMvc.perform(post("/api/v1/accounts").with(TestJwts.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"accountType\":\"" + type + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
    }

    private ResultActions decide(UUID account, String action, String json, RequestPostProcessor who)
            throws Exception {
        return mockMvc.perform(post("/api/v1/admin/accounts/" + account + "/" + action).with(who)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void adminApprovalMakesThePendingAccountActiveAndRecordsReasonAndActor() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID account = apply(customer, "SAVINGS");

        decide(account, "approve", REASON, TestJwts.admin())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        List<AccountStatusHistory> history = historyRepository.findByAccountIdOrderByIdAsc(account);
        assertThat(history).extracting(AccountStatusHistory::getNewStatus)
                .containsExactly(AccountStatus.PENDING, AccountStatus.ACTIVE);
        AccountStatusHistory approval = history.get(1);
        assertThat(approval.getPreviousStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(approval.getReason()).isEqualTo("KYC documents verified");
        assertThat(approval.getActorSubject()).isEqualTo("admin-user-1");
        assertThat(approval.getActorRole()).isEqualTo("ADMIN");
    }

    @Test
    void rejectionIsTerminalKeepsItsReasonAndAllowsAFreshApplication() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID first = apply(customer, "CURRENT");

        decide(first, "reject", "{\"reason\":\"Incomplete documents\"}", TestJwts.admin())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        decide(first, "approve", REASON, TestJwts.admin()).andExpect(status().isConflict());   // terminal
        decide(first, "reject", REASON, TestJwts.admin()).andExpect(status().isConflict());

        UUID second = apply(customer, "CURRENT");   // allowed after a rejection
        assertThat(second).isNotEqualTo(first);

        // the customer's list shows both with status and the rejection reason
        mockMvc.perform(get("/api/v1/accounts").param("customerId", customer.toString())
                        .with(TestJwts.customer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + first + "')].status").value("REJECTED"))
                .andExpect(jsonPath("$.content[?(@.id=='" + first + "')].statusReason").value("Incomplete documents"))
                .andExpect(jsonPath("$.content[?(@.id=='" + second + "')].status").value("PENDING"));
        // history is preserved in full and visible to the owner without the administrator's subject
        mockMvc.perform(get("/api/v1/accounts/" + first + "/status-history").with(TestJwts.customer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].newStatus").value("REJECTED"))
                .andExpect(jsonPath("$[1].reason").value("Incomplete documents"))
                .andExpect(jsonPath("$[1].actorSubject").doesNotExist());
        mockMvc.perform(get("/api/v1/accounts/" + first + "/status-history").with(TestJwts.admin()))
                .andExpect(jsonPath("$[1].actorSubject").value("admin-user-1"));
    }

    @Test
    void onlyAnAdministratorCanApproveOrReject() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID account = apply(customer, "SAVINGS");

        for (String action : new String[] {"approve", "reject"}) {
            decide(account, action, REASON, TestJwts.customer(customer)).andExpect(status().isForbidden());
            decide(account, action, REASON, TestJwts.transactionService()).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/admin/accounts/" + account + "/" + action)
                    .contentType(MediaType.APPLICATION_JSON).content(REASON)).andExpect(status().isUnauthorized());
        }
        assertThat(accountRepository.findById(account).orElseThrow().getStatus()).isEqualTo(AccountStatus.PENDING);
    }

    @Test
    void theServiceLayerRejectsNonAdministratorsToo() {
        com.securebank.account.service.AccountService service = serviceFromContext();
        assertThatThrownBy(() -> service.approve(UUID.randomUUID(), "reason",
                new com.securebank.account.security.CallerContext("c", false, UUID.randomUUID())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.reject(UUID.randomUUID(), "reason",
                new com.securebank.account.security.CallerContext("c", false, UUID.randomUUID())))
                .isInstanceOf(ApiException.class);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.securebank.account.service.AccountService accountService;

    private com.securebank.account.service.AccountService serviceFromContext() {
        return accountService;
    }

    @Test
    void approvalAndRejectionRequireAReason() throws Exception {
        UUID account = apply(UUID.randomUUID(), "SAVINGS");

        for (String action : new String[] {"approve", "reject"}) {
            decide(account, action, "{}", TestJwts.admin()).andExpect(status().isBadRequest());
            decide(account, action, "{\"reason\":\"   \"}", TestJwts.admin()).andExpect(status().isBadRequest());
        }
        assertThat(accountRepository.findById(account).orElseThrow().getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(historyRepository.findByAccountIdOrderByIdAsc(account)).hasSize(1);
    }

    @Test
    void aPendingOrRejectedAccountCannotMoveMoneyAndCannotBeClosed() {
        Account pending = Account.apply(UUID.randomUUID(), "9000000000001", AccountType.SAVINGS, "INR", "c", Instant.now());
        assertThat(pending.getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(pending.getAvailableBalance()).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> pending.credit(new BigDecimal("1.00"), Instant.now())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> pending.debit(new BigDecimal("1.00"), Instant.now())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> pending.close("c", Instant.now())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> pending.freeze("r", "a", Instant.now())).isInstanceOf(ApiException.class);

        pending.reject("no", "admin", Instant.now());
        assertThatThrownBy(() -> pending.credit(new BigDecimal("1.00"), Instant.now())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> pending.approve("late", "admin", Instant.now())).isInstanceOf(ApiException.class);
    }

    @Test
    void theInternalBalanceApiRefusesAPendingAccount() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID account = apply(customer, "SAVINGS");

        mockMvc.perform(post("/internal/accounts/" + account + "/operations")
                        .with(TestJwts.transactionService()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"" + UUID.randomUUID() + "\",\"type\":\"CREDIT\","
                                + "\"amount\":5.00,\"currency\":\"INR\",\"transferId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(accountRepository.findById(account).orElseThrow().getAvailableBalance())
                .isEqualByComparingTo("0.00");
    }
}
