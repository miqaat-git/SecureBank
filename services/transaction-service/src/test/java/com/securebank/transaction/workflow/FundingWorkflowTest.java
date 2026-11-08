package com.securebank.transaction.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securebank.transaction.AbstractIntegrationTest;
import com.securebank.transaction.client.AccountOperationRequest;
import com.securebank.transaction.client.AccountOperationType;
import com.securebank.transaction.client.AccountView;
import com.securebank.transaction.client.LedgerPostRequest;
import com.securebank.transaction.client.RemoteResult;
import com.securebank.transaction.domain.OperationType;
import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferStatus;
import com.securebank.transaction.domain.TransferType;
import com.securebank.transaction.exception.ApiException;
import com.securebank.transaction.security.Caller;
import com.securebank.transaction.service.FundingCommand;
import com.securebank.transaction.service.InitiationResult;
import com.securebank.transaction.service.TransferCommand;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.context.TestPropertySource;

/**
 * Funding reuses the transfer saga with the configured clearing account as the (never caller-chosen) source.
 * Remote services are mocks; the database is the real schema (Flyway on H2). Nothing here proves real settlement.
 */
@TestPropertySource(properties = {
        "securebank.transfer.funding.enabled=true",
        "securebank.transfer.funding.clearing-account-id=33333333-3333-3333-3333-333333333333",
        "securebank.transfer.funding.clearing-customer-id=BANK-CLEARING"})
class FundingWorkflowTest extends AbstractIntegrationTest {

    static final UUID CLEARING = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID ACCOUNT = SRC;   // the caller's own account (owned by CUSTOMER)

    private static String key(String suffix) {
        return "fund-key-" + suffix;
    }

    private static FundingCommand fund(String amount) {
        return new FundingCommand(ACCOUNT, new BigDecimal(amount), "INR");
    }

    private void stubAccounts() {
        when(accounts.findAccount(CLEARING)).thenReturn(Optional.of(new AccountView(CLEARING, "BANK-CLEARING", "ACTIVE", "INR")));
        when(accounts.findAccount(ACCOUNT)).thenReturn(Optional.of(new AccountView(ACCOUNT, CUSTOMER, "ACTIVE", "INR")));
    }

    private void stubSuccess() {
        stubAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.post(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.reverse(any())).thenReturn(RemoteResult.succeeded());
    }

    private List<AccountOperationRequest> sentOps() {
        List<AccountOperationRequest> out = new ArrayList<>();
        Mockito.mockingDetails(accounts).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("applyOperation"))
                .forEach(i -> out.add(i.getArgument(0)));
        return out;
    }

    private long sent(AccountOperationType type, UUID account) {
        return sentOps().stream().filter(o -> o.type() == type && account.equals(o.accountId())).count();
    }

    private Transfer reload(UUID id) {
        return store.require(id);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void fundingDebitsOnlyTheClearingAccountAndPostsABalancedLedgerEntry() {
        stubSuccess();

        Transfer t = transferService.initiateFunding(key("ok"), fund("250.00"), customer()).transfer();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(t.getTransferType()).isEqualTo(TransferType.FUNDING);
        assertThat(t.getSourceAccountId()).isEqualTo(CLEARING);
        assertThat(t.getDestinationAccountId()).isEqualTo(ACCOUNT);
        assertThat(t.getInitiatorCustomerId()).isEqualTo(CUSTOMER);
        assertThat(sentOps()).hasSize(2);
        assertThat(sent(AccountOperationType.DEBIT, CLEARING)).isEqualTo(1);
        assertThat(sent(AccountOperationType.CREDIT, ACCOUNT)).isEqualTo(1);

        ArgumentCaptor<LedgerPostRequest> post = ArgumentCaptor.forClass(LedgerPostRequest.class);
        verify(ledger, times(1)).post(post.capture());
        LedgerPostRequest req = post.getValue();
        assertThat(req.entries()).hasSize(2);
        assertThat(req.entries().get(0).direction()).isEqualTo("DEBIT");
        assertThat(req.entries().get(0).accountId()).isEqualTo(CLEARING);
        assertThat(req.entries().get(1).direction()).isEqualTo("CREDIT");
        assertThat(req.entries().get(1).accountId()).isEqualTo(ACCOUNT);
        assertThat(req.entries().get(0).amount()).isEqualByComparingTo("250.00");
        assertThat(req.entries().get(1).amount()).isEqualByComparingTo("250.00");
        assertThat(req.businessReference()).isEqualTo("TRANSFER-" + t.getId());
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void sameKeySameRequestReplaysAndNeverCreditsOrPostsTwice() {
        stubSuccess();
        Transfer first = transferService.initiateFunding(key("replay"), fund("10.00"), customer()).transfer();

        InitiationResult again = transferService.initiateFunding(key("replay"), fund("10"), customer());

        assertThat(again.replay()).isTrue();
        assertThat(again.transfer().getId()).isEqualTo(first.getId());
        assertThat(transferRepo.count()).isEqualTo(1);
        assertThat(sentOps()).hasSize(2);
        verify(ledger, times(1)).post(any());
    }

    @Test
    void sameKeyWithADifferentRequestIsAConflict() {
        stubSuccess();
        transferService.initiateFunding(key("conflict"), fund("10.00"), customer());

        assertThatThrownBy(() -> transferService.initiateFunding(key("conflict"), fund("11.00"), customer()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThatThrownBy(() -> transferService.initiateFunding(key("conflict"),
                new FundingCommand(UUID.randomUUID(), new BigDecimal("10.00"), "INR"), customer()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(sentOps()).hasSize(2);
        assertThat(transferRepo.count()).isEqualTo(1);
    }

    @Test
    void aTransferKeyCannotBeReusedForFundingEvenWhenRequestFieldsMatch() {
        stubSuccess();

        // The reserved clearing account makes the ordinary transfer fail validation,
        // but its key and request remain persisted.
        TransferCommand sameFields = new TransferCommand(CLEARING, ACCOUNT, new BigDecimal("5.00"), "INR");
        InitiationResult original = transferService.initiate(key("cross-type"), sameFields, customer());

        assertThat(original.transfer().getTransferType()).isEqualTo(TransferType.TRANSFER);
        assertThat(original.transfer().getStatus()).isEqualTo(TransferStatus.FAILED);

        assertThatThrownBy(() -> transferService.initiateFunding(key("cross-type"), fund("5.00"), customer()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void concurrentDuplicateFundingRequestsCreditExactlyOnce() throws Exception {
        stubSuccess();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<InitiationResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<InitiationResult> task = () -> {
                ready.countDown();
                go.await();
                return transferService.initiateFunding(key("race"), fund("20.00"), customer());
            };
            futures.add(pool.submit(task));
        }
        ready.await();
        go.countDown();
        Set<UUID> ids = new HashSet<>();
        int created = 0;
        for (Future<InitiationResult> f : futures) {
            InitiationResult r = f.get(30, TimeUnit.SECONDS);
            ids.add(r.transfer().getId());
            if (!r.replay()) {
                created++;
            }
        }
        pool.shutdownNow();

        assertThat(ids).hasSize(1);
        assertThat(created).isEqualTo(1);
        assertThat(transferRepo.count()).isEqualTo(1);
        assertThat(sent(AccountOperationType.CREDIT, ACCOUNT)).isEqualTo(1);
        assertThat(sent(AccountOperationType.DEBIT, CLEARING)).isEqualTo(1);
        verify(ledger, times(1)).post(any());
    }

    // ------------------------------------------------------------------ request validation (nothing persisted)

    @Test
    void invalidAmountsCurrenciesAndIdentitiesAreRejectedBeforeAnythingIsStored() {
        stubSuccess();
        String[][] bad = {{"0", "INR", "INVALID_AMOUNT"}, {"-5", "INR", "INVALID_AMOUNT"},
                {"1.005", "INR", "INVALID_AMOUNT"}, {"1000000.01", "INR", "AMOUNT_EXCEEDS_LIMIT"},
                {"5.00", "USD", "UNSUPPORTED_CURRENCY"}};
        for (String[] b : bad) {
            String code = b[2];
            assertThatThrownBy(() -> transferService.initiateFunding(key("bad-" + code + "-" + b[0] + b[1]),
                    new FundingCommand(ACCOUNT, new BigDecimal(b[0]), b[1]), customer()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(code));
        }
        assertThatThrownBy(() -> transferService.initiateFunding(key("noid"), fund("5.00"),
                new Caller("sub", null, false)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CUSTOMER_IDENTITY_UNAVAILABLE"));
        assertThat(transferRepo.count()).isZero();
        assertThat(sentOps()).isEmpty();
    }

    // ------------------------------------------------------------------ ownership, status, currency

    private void assertFailedWithoutMovingMoney(String key, FundingCommand cmd, String expectedCode) {
        Transfer t = transferService.initiateFunding(key, cmd, customer()).transfer();
        assertThat(t.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(t.getErrorCode()).isEqualTo(expectedCode);
        verify(accounts, never()).applyOperation(any());
        verify(ledger, never()).post(any());
    }

    @Test
    void anotherCustomersAccountCannotBeFunded() {
        stubAccounts();
        when(accounts.findAccount(ACCOUNT)).thenReturn(Optional.of(new AccountView(ACCOUNT, "CUST-2", "ACTIVE", "INR")));

        assertFailedWithoutMovingMoney(key("notmine"), fund("5.00"), "DESTINATION_ACCOUNT_NOT_OWNED");
    }

    @Test
    void frozenClosedAndMissingDestinationsAreRejected() {
        stubAccounts();
        when(accounts.findAccount(ACCOUNT)).thenReturn(Optional.of(new AccountView(ACCOUNT, CUSTOMER, "FROZEN", "INR")));
        assertFailedWithoutMovingMoney(key("frozen"), fund("5.00"), "DESTINATION_ACCOUNT_INELIGIBLE");

        when(accounts.findAccount(ACCOUNT)).thenReturn(Optional.of(new AccountView(ACCOUNT, CUSTOMER, "CLOSED", "INR")));
        assertFailedWithoutMovingMoney(key("closed"), fund("5.00"), "DESTINATION_ACCOUNT_INELIGIBLE");

        when(accounts.findAccount(ACCOUNT)).thenReturn(Optional.empty());
        assertFailedWithoutMovingMoney(key("missing"), fund("5.00"), "DESTINATION_ACCOUNT_NOT_FOUND");
    }

    @Test
    void currencyMismatchIsRejected() {
        stubAccounts();
        when(accounts.findAccount(ACCOUNT)).thenReturn(Optional.of(new AccountView(ACCOUNT, CUSTOMER, "ACTIVE", "USD")));

        assertFailedWithoutMovingMoney(key("currency"), fund("5.00"), "CURRENCY_MISMATCH");
    }

    @Test
    void aMisconfiguredOrUnusableClearingAccountNeverFundsAnything() {
        stubAccounts();
        when(accounts.findAccount(CLEARING)).thenReturn(Optional.of(new AccountView(CLEARING, "SOMEONE-ELSE", "ACTIVE", "INR")));
        assertFailedWithoutMovingMoney(key("clr-owner"), fund("5.00"), "CLEARING_ACCOUNT_INVALID");

        when(accounts.findAccount(CLEARING)).thenReturn(Optional.of(new AccountView(CLEARING, "BANK-CLEARING", "FROZEN", "INR")));
        assertFailedWithoutMovingMoney(key("clr-frozen"), fund("5.00"), "CLEARING_ACCOUNT_INELIGIBLE");

        when(accounts.findAccount(CLEARING)).thenReturn(Optional.empty());
        assertFailedWithoutMovingMoney(key("clr-missing"), fund("5.00"), "CLEARING_ACCOUNT_NOT_FOUND");
    }

    @Test
    void theClearingAccountCanNeverBeUsedByAnOrdinaryTransferInEitherDirection() {
        stubAccounts();
        Caller bank = new Caller("bank-user", "BANK-CLEARING", false);

        Transfer out = transferService.initiate(key("drain"),
                new TransferCommand(CLEARING, ACCOUNT, new BigDecimal("5.00"), "INR"), bank).transfer();
        Transfer in = transferService.initiate(key("deposit"),
                new TransferCommand(ACCOUNT, CLEARING, new BigDecimal("5.00"), "INR"), customer()).transfer();

        assertThat(out.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(out.getErrorCode()).isEqualTo("ACCOUNT_RESERVED");
        assertThat(in.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(in.getErrorCode()).isEqualTo("ACCOUNT_RESERVED");
        verify(accounts, never()).applyOperation(any());
        verify(ledger, never()).post(any());
    }

    // ------------------------------------------------------------------ remote failures and compensation

    @Test
    void exhaustedClearingBalanceFailsWithoutAnyCreditOrLedgerEntry() {
        stubAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.rejected("INSUFFICIENT_FUNDS"));

        Transfer t = transferService.initiateFunding(key("float"), fund("5.00"), customer()).transfer();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(t.getErrorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(sent(AccountOperationType.CREDIT, ACCOUNT)).isZero();
        verify(ledger, never()).post(any());
    }

    @Test
    void ledgerRejectionReturnsTheClearingDebitAndNeverCreditsTheCustomer() {
        stubAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.post(any())).thenReturn(RemoteResult.rejected("UNBALANCED_ENTRY"));

        Transfer t = transferService.initiateFunding(key("ledgerrej"), fund("5.00"), customer()).transfer();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
        assertThat(sent(AccountOperationType.CREDIT, ACCOUNT)).isZero();
        assertThat(sent(AccountOperationType.DEBIT, CLEARING)).isEqualTo(1);
        assertThat(sent(AccountOperationType.REVERSE_DEBIT, CLEARING)).isEqualTo(1);
        verify(ledger, never()).reverse(any());   // nothing was posted, so nothing to reverse
    }

    @Test
    void creditRejectedAfterPostingReversesTheLedgerEntryAndTheClearingDebit() {
        stubAccounts();
        when(accounts.applyOperation(any())).thenAnswer(inv -> {
            AccountOperationRequest r = inv.getArgument(0);
            return r.type() == AccountOperationType.CREDIT ? RemoteResult.rejected("ACCOUNT_CLOSED")
                    : RemoteResult.succeeded();
        });
        when(ledger.post(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.reverse(any())).thenReturn(RemoteResult.succeeded());

        Transfer t = transferService.initiateFunding(key("creditrej"), fund("5.00"), customer()).transfer();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
        verify(ledger, times(1)).post(any());
        verify(ledger, times(1)).reverse(any());
        assertThat(sent(AccountOperationType.REVERSE_DEBIT, CLEARING)).isEqualTo(1);
    }

    @Test
    void aTimeoutOnTheCreditIsResolvedByLookupWithoutASecondCreditOrLedgerEntry() {
        stubAccounts();
        when(accounts.applyOperation(any())).thenAnswer(inv -> {
            AccountOperationRequest r = inv.getArgument(0);
            return r.type() == AccountOperationType.CREDIT ? RemoteResult.unknown("IO_ERROR_OR_TIMEOUT")
                    : RemoteResult.succeeded();
        });
        when(accounts.getOperation(any(), any())).thenReturn(RemoteResult.succeeded());
        when(ledger.post(any())).thenReturn(RemoteResult.succeeded());

        Transfer t = transferService.initiateFunding(key("credit-timeout"), fund("5.00"), customer()).transfer();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(sent(AccountOperationType.CREDIT, ACCOUNT)).isEqualTo(1);
        verify(ledger, times(1)).post(any());
        UUID creditOp = operationStore.byType(t.getId()).get(OperationType.CREDIT_DESTINATION).getOperationId();
        verify(accounts).getOperation(ACCOUNT, creditOp);
    }

    @Test
    void anAmbiguousDebitIsHeldForReconciliationAndNeverCreditedOrReplayedIntoASecondWorkflow() {
        stubAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.unknown("IO_ERROR_OR_TIMEOUT"));
        when(accounts.getOperation(any(), any())).thenReturn(RemoteResult.unknown("IO_ERROR_OR_TIMEOUT"));
        when(ledger.post(any())).thenReturn(RemoteResult.succeeded());

        Transfer t = transferService.initiateFunding(key("ambig"), fund("5.00"), customer()).transfer();
        processor.process(t.getId());
        processor.process(t.getId());

        assertThat(reload(t.getId()).getStatus()).isEqualTo(TransferStatus.RECONCILIATION_REQUIRED);
        assertThat(sent(AccountOperationType.CREDIT, ACCOUNT)).isZero();
        verify(ledger, never()).post(any());
        long debitsSoFar = sent(AccountOperationType.DEBIT, CLEARING);

        // the customer retrying with the same key gets the existing transfer back: no new workflow, no new debit
        InitiationResult retry = transferService.initiateFunding(key("ambig"), fund("5.00"), customer());
        assertThat(retry.replay()).isTrue();
        assertThat(retry.transfer().getId()).isEqualTo(t.getId());
        assertThat(sent(AccountOperationType.DEBIT, CLEARING)).isEqualTo(debitsSoFar);
        assertThat(transferRepo.count()).isEqualTo(1);

        // the operator confirms the debit really happened: the SAME operation ids finish the funding exactly once
        when(accounts.applyOperation(any())).thenAnswer(inv -> {
            AccountOperationRequest r = inv.getArgument(0);
            return RemoteResult.succeeded();
        });
        when(accounts.getOperation(any(), any())).thenReturn(RemoteResult.succeeded());
        Transfer done = transferService.reconcile(t.getId());

        assertThat(done.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(sent(AccountOperationType.CREDIT, ACCOUNT)).isEqualTo(1);
        assertThat(sent(AccountOperationType.DEBIT, CLEARING)).isEqualTo(debitsSoFar);
        verify(ledger, times(1)).post(any());
    }
}
