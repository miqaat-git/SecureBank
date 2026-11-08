package com.securebank.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.account.domain.Account;
import com.securebank.account.support.AbstractIntegrationTest;
import com.securebank.account.support.TestJwts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Internal idempotent balance operations: authorization, idempotency, invalid updates, account state rules. */
class InternalBalanceOperationTest extends AbstractIntegrationTest {

    private ResultActions send(RequestPostProcessor caller, UUID accountId, String type, String amount,
                               String key, UUID reference) throws Exception {
        String json = "{\"operationType\":\"" + type + "\",\"amount\":" + amount + ",\"idempotencyKey\":\"" + key
                + "\",\"referenceId\":\"" + reference + "\"}";
        var request = post("/internal/v1/accounts/" + accountId + "/balance-operations")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (caller != null) {
            request = request.with(caller);
        }
        return mockMvc.perform(request);
    }

    private ResultActions apply(UUID accountId, String type, String amount, String key) throws Exception {
        return send(TestJwts.transactionService(), accountId, type, amount, key, UUID.randomUUID());
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getAvailableBalance();
    }

    @Test
    void creditAndDebitChangeTheBalanceExactly() throws Exception {
        Account account = insertAccount(UUID.randomUUID());

        apply(account.getId(), "CREDIT", "100.00", "key-credit-0001")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(content().string(containsString("\"resultingBalance\":100.00")));
        apply(account.getId(), "DEBIT", "40.25", "key-debit-0001")
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"resultingBalance\":59.75")));

        assertThat(balanceOf(account)).isEqualByComparingTo("59.75");
        assertThat(balanceOperationRepository.count()).isEqualTo(2);
    }

    @Test
    void replayWithTheSameKeyAndPayloadIsAppliedOnlyOnce() throws Exception {
        Account account = insertAccount(UUID.randomUUID());
        UUID reference = UUID.randomUUID();

        send(TestJwts.transactionService(), account.getId(), "CREDIT", "100.00", "key-replay-0001", reference)
                .andExpect(status().isCreated());
        send(TestJwts.transactionService(), account.getId(), "CREDIT", "100.00", "key-replay-0001", reference)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(content().string(containsString("\"resultingBalance\":100.00")));

        assertThat(balanceOf(account)).isEqualByComparingTo("100.00");
        assertThat(balanceOperationRepository.count()).isEqualTo(1);
    }

    @Test
    void reusingAKeyWithADifferentRequestIsRejected() throws Exception {
        Account account = insertAccount(UUID.randomUUID());
        Account other = insertAccount(UUID.randomUUID());
        UUID reference = UUID.randomUUID();
        send(TestJwts.transactionService(), account.getId(), "CREDIT", "100.00", "key-reuse-00001", reference)
                .andExpect(status().isCreated());

        send(TestJwts.transactionService(), account.getId(), "CREDIT", "150.00", "key-reuse-00001", reference)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        send(TestJwts.transactionService(), account.getId(), "CREDIT", "100.00", "key-reuse-00001",
                UUID.randomUUID()).andExpect(status().isConflict());
        send(TestJwts.transactionService(), account.getId(), "DEBIT", "100.00", "key-reuse-00001", reference)
                .andExpect(status().isConflict());
        send(TestJwts.transactionService(), other.getId(), "CREDIT", "100.00", "key-reuse-00001", reference)
                .andExpect(status().isConflict());

        assertThat(balanceOf(account)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(other)).isEqualByComparingTo("0.00");
    }

    @Test
    void debitCannotMakeTheBalanceNegativeAndRecordsNothing() throws Exception {
        Account account = insertAccount(UUID.randomUUID());
        apply(account.getId(), "CREDIT", "10.00", "key-fund-000001").andExpect(status().isCreated());

        apply(account.getId(), "DEBIT", "10.01", "key-overdraw-01")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(balanceOf(account)).isEqualByComparingTo("10.00");
        assertThat(balanceOperationRepository.count()).isEqualTo(1);

        apply(account.getId(), "DEBIT", "10.00", "key-exact-000001").andExpect(status().isCreated());
        assertThat(balanceOf(account)).isEqualByComparingTo("0.00");
    }

    @Test
    void balanceCannotExceedTheMaximum() throws Exception {
        Account account = insertAccount(UUID.randomUUID());
        apply(account.getId(), "CREDIT", "99999999999999999.99", "key-max-0000001").andExpect(status().isCreated());

        apply(account.getId(), "CREDIT", "0.01", "key-max-0000002")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BALANCE_LIMIT_EXCEEDED"));
        assertThat(balanceOf(account)).isEqualByComparingTo("99999999999999999.99");
    }

    @Test
    void invalidAmountsAndBodiesAreRejectedWithoutChangingAnything() throws Exception {
        Account account = insertAccount(UUID.randomUUID());
        UUID id = account.getId();

        apply(id, "CREDIT", "0.001", "key-bad-0000001").andExpect(status().isBadRequest());
        apply(id, "CREDIT", "-5.00", "key-bad-0000002").andExpect(status().isBadRequest());
        apply(id, "CREDIT", "0", "key-bad-0000003").andExpect(status().isBadRequest());
        apply(id, "CREDIT", "1E+30", "key-bad-0000004").andExpect(status().isBadRequest());
        apply(id, "CREDIT", "5.00", "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        apply(id, "TRANSFER", "5.00", "key-bad-0000005").andExpect(status().isBadRequest());

        mockMvc.perform(post("/internal/v1/accounts/" + id + "/balance-operations").with(TestJwts.transactionService())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationType\":\"CREDIT\",\"idempotencyKey\":\"key-bad-0000006\"}"))
                .andExpect(status().isBadRequest());

        assertThat(balanceOf(account)).isEqualByComparingTo("0.00");
        assertThat(balanceOperationRepository.count()).isZero();
    }

    @Test
    void frozenAndClosedAccountsRejectBalanceOperations() throws Exception {
        Account frozen = insertAccount(UUID.randomUUID());
        frozen.freeze("review", "admin", Instant.now());
        accountRepository.saveAndFlush(frozen);
        Account closed = insertAccount(UUID.randomUUID());
        closed.close("owner", Instant.now());
        accountRepository.saveAndFlush(closed);

        apply(frozen.getId(), "CREDIT", "1.00", "key-frozen-00001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_OPERATIONAL"));
        apply(frozen.getId(), "DEBIT", "1.00", "key-frozen-00002").andExpect(status().isConflict());
        apply(closed.getId(), "CREDIT", "1.00", "key-closed-00001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_OPERATIONAL"));

        assertThat(balanceOperationRepository.count()).isZero();
    }

    @Test
    void unknownAccountIsNotFound() throws Exception {
        apply(UUID.randomUUID(), "CREDIT", "1.00", "key-unknown-0001")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void onlyTheTransactionServiceMayCallInternalEndpoints() throws Exception {
        UUID owner = UUID.randomUUID();
        Account account = insertAccount(owner);

        send(TestJwts.customer(owner), account.getId(), "CREDIT", "50.00", "key-authz-000001", UUID.randomUUID())
                .andExpect(status().isForbidden());
        send(TestJwts.admin(), account.getId(), "CREDIT", "50.00", "key-authz-000002", UUID.randomUUID())
                .andExpect(status().isForbidden());
        send(null, account.getId(), "CREDIT", "50.00", "key-authz-000003", UUID.randomUUID())
                .andExpect(status().isUnauthorized());

        assertThat(balanceOf(account)).isEqualByComparingTo("0.00");
        assertThat(balanceOperationRepository.count()).isZero();
    }
}
