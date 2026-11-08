package com.securebank.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/**
 * Canonical Transaction -> Account contract over the real controller, validation, JSON and database mapping.
 * The request JSON mirrors what the Transaction Service's {@code AccountOperationRequest} serialises to (same field
 * names, {@code accountId} only in the path, {@code reversesOperationId} present as null for non-reversals).
 * Uses the isolated H2 test database, so it does not prove MySQL behaviour.
 */
class InternalOperationContractTest extends AbstractIntegrationTest {

    private static String body(UUID operationId, String type, String amount, String currency, UUID transferId,
                               UUID reverses) {
        return "{\"operationId\":\"" + operationId + "\",\"type\":\"" + type + "\",\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\",\"transferId\":\"" + transferId
                + "\",\"reversesOperationId\":" + (reverses == null ? "null" : "\"" + reverses + "\"") + "}";
    }

    private ResultActions send(RequestPostProcessor caller, UUID accountId, String json) throws Exception {
        var request = post("/internal/accounts/" + accountId + "/operations")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (caller != null) {
            request = request.with(caller);
        }
        return mockMvc.perform(request);
    }

    private ResultActions op(UUID accountId, UUID operationId, String type, String amount, UUID transferId,
                             UUID reverses) throws Exception {
        return send(TestJwts.transactionService(), accountId,
                body(operationId, type, amount, "INR", transferId, reverses));
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getAvailableBalance();
    }

    private Account fundedAccount(String amount) throws Exception {
        Account account = insertAccount(UUID.randomUUID());
        op(account.getId(), UUID.randomUUID(), "CREDIT", amount, UUID.randomUUID(), null)
                .andExpect(status().isCreated());
        return account;
    }

    @Test
    void creditDebitAndReversalReturnSucceededAndChangeTheBalanceExactly() throws Exception {
        Account account = fundedAccount("100.00");
        UUID transfer = UUID.randomUUID();
        UUID debit = UUID.randomUUID();
        UUID reversal = UUID.randomUUID();

        op(account.getId(), debit, "DEBIT", "40.25", transfer, null)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.operationId").value(debit.toString()))
                .andExpect(jsonPath("$.type").value("DEBIT"))
                .andExpect(jsonPath("$.transferId").value(transfer.toString()))
                .andExpect(jsonPath("$.replayed").value(false));
        assertThat(balanceOf(account)).isEqualByComparingTo("59.75");

        op(account.getId(), reversal, "REVERSE_DEBIT", "40.25", transfer, debit)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.type").value("REVERSE_DEBIT"));
        assertThat(balanceOf(account)).isEqualByComparingTo("100.00");
        // Original rows are never changed: credit + debit + reversal.
        assertThat(balanceOperationRepository.count()).isEqualTo(3);
    }

    @Test
    void replayWithTheSameOperationIdIsAppliedOnceAndReportedAsReplayed() throws Exception {
        Account account = fundedAccount("100.00");
        UUID transfer = UUID.randomUUID();
        UUID debit = UUID.randomUUID();

        op(account.getId(), debit, "DEBIT", "10.00", transfer, null).andExpect(status().isCreated());
        op(account.getId(), debit, "DEBIT", "10.00", transfer, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.replayed").value(true));

        assertThat(balanceOf(account)).isEqualByComparingTo("90.00");
        assertThat(balanceOperationRepository.count()).isEqualTo(2);
    }

    @Test
    void reusingAnOperationIdWithADifferentPayloadIsRejected() throws Exception {
        Account account = fundedAccount("100.00");
        UUID transfer = UUID.randomUUID();
        UUID debit = UUID.randomUUID();
        op(account.getId(), debit, "DEBIT", "10.00", transfer, null).andExpect(status().isCreated());

        op(account.getId(), debit, "DEBIT", "11.00", transfer, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        op(account.getId(), debit, "DEBIT", "10.00", UUID.randomUUID(), null).andExpect(status().isConflict());
        op(account.getId(), debit, "CREDIT", "10.00", transfer, null).andExpect(status().isConflict());

        assertThat(balanceOf(account)).isEqualByComparingTo("90.00");
    }

    @Test
    void aSecondReversalOfTheSameDebitWithAnotherOperationIdIsRefused() throws Exception {
        Account account = fundedAccount("100.00");
        UUID transfer = UUID.randomUUID();
        UUID debit = UUID.randomUUID();
        UUID reversal = UUID.randomUUID();
        op(account.getId(), debit, "DEBIT", "30.00", transfer, null).andExpect(status().isCreated());
        op(account.getId(), reversal, "REVERSE_DEBIT", "30.00", transfer, debit).andExpect(status().isCreated());

        op(account.getId(), UUID.randomUUID(), "REVERSE_DEBIT", "30.00", transfer, debit)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_ALREADY_REVERSED"));
        // The original reversal is still an idempotent replay.
        op(account.getId(), reversal, "REVERSE_DEBIT", "30.00", transfer, debit).andExpect(status().isOk());

        assertThat(balanceOf(account)).isEqualByComparingTo("100.00");
    }

    @Test
    void reversalMustReferenceAMatchingDebitOnTheSameAccount() throws Exception {
        Account account = fundedAccount("100.00");
        Account other = fundedAccount("100.00");
        UUID transfer = UUID.randomUUID();
        UUID debit = UUID.randomUUID();
        op(account.getId(), debit, "DEBIT", "30.00", transfer, null).andExpect(status().isCreated());

        // unknown debit
        op(account.getId(), UUID.randomUUID(), "REVERSE_DEBIT", "30.00", transfer, UUID.randomUUID())
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_REVERSAL"));
        // different amount
        op(account.getId(), UUID.randomUUID(), "REVERSE_DEBIT", "29.99", transfer, debit)
                .andExpect(status().isUnprocessableEntity());
        // different transfer
        op(account.getId(), UUID.randomUUID(), "REVERSE_DEBIT", "30.00", UUID.randomUUID(), debit)
                .andExpect(status().isUnprocessableEntity());
        // debit belongs to another account
        op(other.getId(), UUID.randomUUID(), "REVERSE_DEBIT", "30.00", transfer, debit)
                .andExpect(status().isUnprocessableEntity());

        assertThat(balanceOf(account)).isEqualByComparingTo("70.00");
        assertThat(balanceOf(other)).isEqualByComparingTo("100.00");
    }

    @Test
    void reversesOperationIdIsRequiredExactlyForReversals() throws Exception {
        Account account = fundedAccount("100.00");
        UUID transfer = UUID.randomUUID();

        op(account.getId(), UUID.randomUUID(), "REVERSE_DEBIT", "1.00", transfer, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        op(account.getId(), UUID.randomUUID(), "DEBIT", "1.00", transfer, UUID.randomUUID())
                .andExpect(status().isBadRequest());
        assertThat(balanceOf(account)).isEqualByComparingTo("100.00");
    }

    @Test
    void invalidBodiesAndCurrencyMismatchChangeNothing() throws Exception {
        Account account = fundedAccount("100.00");
        UUID id = account.getId();
        UUID transfer = UUID.randomUUID();

        send(TestJwts.transactionService(), id, body(UUID.randomUUID(), "DEBIT", "1.00", "USD", transfer, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
        send(TestJwts.transactionService(), id, body(UUID.randomUUID(), "DEBIT", "1.00", "inr", transfer, null))
                .andExpect(status().isBadRequest());
        op(id, UUID.randomUUID(), "DEBIT", "0.001", transfer, null).andExpect(status().isBadRequest());
        op(id, UUID.randomUUID(), "TRANSFER", "1.00", transfer, null).andExpect(status().isBadRequest());
        // unknown field is rejected (fail-on-unknown-properties), e.g. the legacy field names
        send(TestJwts.transactionService(), id,
                "{\"operationType\":\"DEBIT\",\"amount\":1.00,\"idempotencyKey\":\"key-legacy-0001\","
                        + "\"referenceId\":\"" + transfer + "\"}").andExpect(status().isBadRequest());

        assertThat(balanceOf(account)).isEqualByComparingTo("100.00");
    }

    @Test
    void insufficientFundsIsAnExplicitRejection() throws Exception {
        Account account = fundedAccount("10.00");
        op(account.getId(), UUID.randomUUID(), "DEBIT", "10.01", UUID.randomUUID(), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        assertThat(balanceOf(account)).isEqualByComparingTo("10.00");
    }

    @Test
    void statusLookupReturnsTheRecordedOperationOrNotFound() throws Exception {
        Account account = fundedAccount("100.00");
        Account other = insertAccount(UUID.randomUUID());
        UUID debit = UUID.randomUUID();
        op(account.getId(), debit, "DEBIT", "5.00", UUID.randomUUID(), null).andExpect(status().isCreated());

        mockMvc.perform(get("/internal/accounts/" + account.getId() + "/operations/" + debit)
                        .with(TestJwts.transactionService()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.operationId").value(debit.toString()));
        mockMvc.perform(get("/internal/accounts/" + account.getId() + "/operations/" + UUID.randomUUID())
                        .with(TestJwts.transactionService()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("OPERATION_NOT_FOUND"));
        // The operation exists, but not on this account: no information leak across accounts.
        mockMvc.perform(get("/internal/accounts/" + other.getId() + "/operations/" + debit)
                        .with(TestJwts.transactionService()))
                .andExpect(status().isNotFound());
    }

    @Test
    void internalAccountViewExposesOnlyIdentityStatusAndCurrency() throws Exception {
        UUID customer = UUID.randomUUID();
        Account account = insertAccount(customer);

        mockMvc.perform(get("/internal/accounts/" + account.getId()).with(TestJwts.transactionService()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(account.getId().toString()))
                .andExpect(jsonPath("$.customerId").value(customer.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.availableBalance").doesNotExist());
        mockMvc.perform(get("/internal/accounts/" + UUID.randomUUID()).with(TestJwts.transactionService()))
                .andExpect(status().isNotFound());
    }

    @Test
    void frozenAccountRejectsOperationsIncludingReversals() throws Exception {
        Account account = fundedAccount("100.00");
        UUID transfer = UUID.randomUUID();
        UUID debit = UUID.randomUUID();
        op(account.getId(), debit, "DEBIT", "10.00", transfer, null).andExpect(status().isCreated());
        Account loaded = accountRepository.findById(account.getId()).orElseThrow();
        loaded.freeze("review", "admin", Instant.now());
        accountRepository.saveAndFlush(loaded);

        // Documented limitation: compensation is NOT allowed on a frozen account (blueprint rule); the transfer
        // then escalates to RECONCILIATION_REQUIRED for an operator.
        op(account.getId(), UUID.randomUUID(), "REVERSE_DEBIT", "10.00", transfer, debit)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_OPERATIONAL"));
    }

    @Test
    void onlyTheTransactionServiceMayCallTheInternalContract() throws Exception {
        UUID owner = UUID.randomUUID();
        Account account = insertAccount(owner);
        String json = body(UUID.randomUUID(), "CREDIT", "50.00", "INR", UUID.randomUUID(), null);
        UUID someOperation = UUID.randomUUID();

        send(TestJwts.customer(owner), account.getId(), json).andExpect(status().isForbidden());
        send(TestJwts.admin(), account.getId(), json).andExpect(status().isForbidden());
        send(null, account.getId(), json).andExpect(status().isUnauthorized());
        for (RequestPostProcessor caller : new RequestPostProcessor[]{TestJwts.customer(owner), TestJwts.admin()}) {
            mockMvc.perform(get("/internal/accounts/" + account.getId()).with(caller))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/internal/accounts/" + account.getId() + "/operations/" + someOperation)
                    .with(caller)).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/internal/accounts/" + account.getId())).andExpect(status().isUnauthorized());

        assertThat(balanceOf(account)).isEqualByComparingTo("0.00");
        assertThat(balanceOperationRepository.count()).isZero();
    }

    @Test
    void legacyEndpointDoesNotAcceptReversals() throws Exception {
        Account account = fundedAccount("100.00");
        mockMvc.perform(post("/internal/v1/accounts/" + account.getId() + "/balance-operations")
                        .with(TestJwts.transactionService()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationType\":\"REVERSE_DEBIT\",\"amount\":1.00,"
                                + "\"idempotencyKey\":\"key-legacy-rev1\",\"referenceId\":\"" + UUID.randomUUID()
                                + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(balanceOf(account)).isEqualByComparingTo("100.00");
    }
}
