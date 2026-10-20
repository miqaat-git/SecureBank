package com.securebank.transaction.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.securebank.transaction.AbstractIntegrationTest;
import com.securebank.transaction.client.AccountOperationRequest;
import com.securebank.transaction.client.AccountOperationType;
import com.securebank.transaction.client.AccountView;
import com.securebank.transaction.client.LedgerPostRequest;
import com.securebank.transaction.client.RemoteCallException;
import com.securebank.transaction.client.RemoteResult;
import com.securebank.transaction.domain.IllegalStateTransitionException;
import com.securebank.transaction.domain.OperationStatus;
import com.securebank.transaction.domain.OperationType;
import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferOperation;
import com.securebank.transaction.domain.TransferStatus;
import com.securebank.transaction.exception.ApiException;
import com.securebank.transaction.security.Caller;
import com.securebank.transaction.service.InitiationResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

class TransferWorkflowTest extends AbstractIntegrationTest {

    private static String key(String suffix) {
        return "test-key-" + suffix;
    }

    private Transfer reload(UUID id) {
        return store.require(id);
    }

    private List<AccountOperationRequest> sentAccountOps() {
        ArgumentCaptor<AccountOperationRequest> captor = ArgumentCaptor.forClass(AccountOperationRequest.class);
        verify(accounts, atLeast(0)).applyOperation(captor.capture());
        return captor.getAllValues();
    }

    private long sentOfType(AccountOperationType type) {
        return sentAccountOps().stream().filter(r -> r.type() == type).count();
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void happyPathCompletesOnlyAfterDebitLedgerAndCreditAllSucceed() {
        stubEverythingSucceeds();

        InitiationResult result = transferService.initiate(key("happy"), cmd("250.00"), customer());

        Transfer t = result.transfer();
        assertThat(result.replay()).isFalse();
        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(t.getCompletedAt()).isNotNull();
        assertThat(t.getErrorCode()).isNull();

        InOrder order = inOrder(accounts, ledger);
        order.verify(accounts).applyOperation(argThat(isType(AccountOperationType.DEBIT)));
        order.verify(ledger).post(any());
        order.verify(accounts).applyOperation(argThat(isType(AccountOperationType.CREDIT)));

        assertThat(historyOf(t.getId())).containsExactly(TransferStatus.INITIATED, TransferStatus.VALIDATING,
                TransferStatus.PROCESSING, TransferStatus.COMPLETED);
        Map<OperationType, TransferOperation> ops = operationStore.byType(t.getId());
        assertThat(ops.keySet()).containsExactlyInAnyOrder(OperationType.DEBIT_SOURCE, OperationType.LEDGER_POST,
                OperationType.CREDIT_DESTINATION);
        assertThat(ops.values()).allMatch(o -> o.getStatus() == OperationStatus.SUCCEEDED && o.getAttemptCount() == 1);
    }

    @Test
    void ledgerEntryIsBalancedAndUsesUniqueBusinessReference() {
        stubEverythingSucceeds();

        Transfer t = transferService.initiate(key("ledger"), cmd("75.50"), customer()).transfer();

        ArgumentCaptor<LedgerPostRequest> captor = ArgumentCaptor.forClass(LedgerPostRequest.class);
        verify(ledger).post(captor.capture());
        LedgerPostRequest req = captor.getValue();
        assertThat(req.businessReference()).isEqualTo("TRANSFER-" + t.getId());
        assertThat(req.entries()).hasSize(2);
        assertThat(req.entries().get(0).direction()).isEqualTo("DEBIT");
        assertThat(req.entries().get(0).accountId()).isEqualTo(SRC);
        assertThat(req.entries().get(1).direction()).isEqualTo("CREDIT");
        assertThat(req.entries().get(1).accountId()).isEqualTo(DST);
        assertThat(req.entries().get(0).amount()).isEqualByComparingTo(req.entries().get(1).amount());
        assertThat(req.idempotencyKey())
                .isEqualTo(operationStore.byType(t.getId()).get(OperationType.LEDGER_POST).getOperationId());
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void sameKeySameRequestIsAReplayAndNeverRepeatsFinancialOperations() {
        stubEverythingSucceeds();

        InitiationResult first = transferService.initiate(key("replay"), cmd("10.00"), customer());
        InitiationResult second = transferService.initiate(key("replay"), cmd("10.00"), customer());

        assertThat(second.replay()).isTrue();
        assertThat(second.transfer().getId()).isEqualTo(first.transfer().getId());
        assertThat(transferRepo.count()).isEqualTo(1);
        assertThat(sentOfType(AccountOperationType.DEBIT)).isEqualTo(1);
        assertThat(sentOfType(AccountOperationType.CREDIT)).isEqualTo(1);
        verify(ledger, times(1)).post(any());
    }

    @Test
    void sameKeyWithDifferentRequestIsRejectedAsConflict() {
        stubEverythingSucceeds();
        transferService.initiate(key("conflict"), cmd("10.00"), customer());

        assertThatThrownBy(() -> transferService.initiate(key("conflict"), cmd("11.00"), customer()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThatThrownBy(() -> transferService.initiate(key("conflict"), cmd("10.00"),
                new Caller("other", "CUST-9", false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(transferRepo.count()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateRequestsStartExactlyOneWorkflow() throws Exception {
        stubEverythingSucceeds();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<InitiationResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<InitiationResult> task = () -> {
                ready.countDown();
                go.await();
                return transferService.initiate(key("race"), cmd("20.00"), customer());
            };
            futures.add(pool.submit(task));
        }
        ready.await();
        go.countDown();
        Set<UUID> ids = new java.util.HashSet<>();
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
        assertThat(sentOfType(AccountOperationType.DEBIT)).isEqualTo(1);
        assertThat(sentOfType(AccountOperationType.CREDIT)).isEqualTo(1);
        verify(ledger, times(1)).post(any());
    }

    // ------------------------------------------------------------------ validation failures

    @Test
    void frozenSourceFailsWithoutAnyFinancialCall() {
        when(accounts.findAccount(SRC)).thenReturn(Optional.of(new AccountView(SRC, CUSTOMER, "FROZEN", "INR")));
        when(accounts.findAccount(DST)).thenReturn(Optional.of(new AccountView(DST, "CUST-2", "ACTIVE", "INR")));

        Transfer t = transferService.initiate(key("frozen"), cmd("10.00"), customer()).transfer();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(t.getErrorCode()).isEqualTo("SOURCE_ACCOUNT_INELIGIBLE");
        verify(accounts, never()).applyOperation(any());
        verifyNoInteractions(ledger);
        assertThat(operationRepo.count()).isZero();
    }

    @Test
    void closedDestinationMissingDestinationNotOwnedSourceAndCurrencyMismatchAreRejected() {
        when(accounts.findAccount(SRC)).thenReturn(Optional.of(new AccountView(SRC, CUSTOMER, "ACTIVE", "INR")));
        when(accounts.findAccount(DST)).thenReturn(Optional.of(new AccountView(DST, "CUST-2", "CLOSED", "INR")));
        assertThat(transferService.initiate(key("closed"), cmd("1.00"), customer()).transfer().getErrorCode())
                .isEqualTo("DESTINATION_ACCOUNT_INELIGIBLE");

        when(accounts.findAccount(DST)).thenReturn(Optional.empty());
        assertThat(transferService.initiate(key("nodest"), cmd("1.00"), customer()).transfer().getErrorCode())
                .isEqualTo("DESTINATION_ACCOUNT_NOT_FOUND");

        when(accounts.findAccount(SRC)).thenReturn(Optional.of(new AccountView(SRC, "SOMEONE-ELSE", "ACTIVE", "INR")));
        assertThat(transferService.initiate(key("notowned"), cmd("1.00"), customer()).transfer().getErrorCode())
                .isEqualTo("SOURCE_ACCOUNT_NOT_OWNED");

        when(accounts.findAccount(SRC)).thenReturn(Optional.of(new AccountView(SRC, CUSTOMER, "ACTIVE", "USD")));
        when(accounts.findAccount(DST)).thenReturn(Optional.of(new AccountView(DST, "CUST-2", "ACTIVE", "INR")));
        assertThat(transferService.initiate(key("ccy"), cmd("1.00"), customer()).transfer().getErrorCode())
                .isEqualTo("CURRENCY_MISMATCH");

        verify(accounts, never()).applyOperation(any());
        verifyNoInteractions(ledger);
    }

    @Test
    void accountServiceDownDuringValidationEndsInFailedOnlyAfterRetriesAndNeverMovesMoney() {
        when(accounts.findAccount(any())).thenThrow(new RemoteCallException("ACCOUNT_SERVICE_UNREACHABLE"));

        Transfer t = transferService.initiate(key("down"), cmd("5.00"), customer()).transfer();
        assertThat(t.getStatus()).isEqualTo(TransferStatus.VALIDATING);
        processor.process(t.getId());
        assertThat(reload(t.getId()).getStatus()).isEqualTo(TransferStatus.VALIDATING);
        processor.process(t.getId());

        Transfer end = reload(t.getId());
        assertThat(end.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(end.getErrorCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        verify(accounts, never()).applyOperation(any());
    }

    // ------------------------------------------------------------------ definitive rejections

    @Test
    void insufficientFundsFailsTheTransferWithoutTouchingTheLedger() {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.rejected("INSUFFICIENT_FUNDS"));

        Transfer t = transferService.initiate(key("nofunds"), cmd("999.00"), customer()).transfer();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(t.getErrorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        verifyNoInteractions(ledger);
        assertThat(operationStore.byType(t.getId()).get(OperationType.DEBIT_SOURCE).getStatus())
                .isEqualTo(OperationStatus.REJECTED);
    }

    @Test
    void ledgerRejectionReversesTheDebitAndNeverCredits() {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.post(any())).thenReturn(RemoteResult.rejected("UNBALANCED_ENTRY"));

        Transfer t = transferService.initiate(key("ledgerrej"), cmd("30.00"), customer()).transfer();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
        assertThat(t.getErrorCode()).isEqualTo("LEDGER_POSTING_REJECTED");
        assertThat(sentOfType(AccountOperationType.CREDIT)).isZero();
        List<AccountOperationRequest> sent = sentAccountOps();
        AccountOperationRequest debit = sent.stream().filter(r -> r.type() == AccountOperationType.DEBIT).findFirst().orElseThrow();
        AccountOperationRequest reversal = sent.stream().filter(r -> r.type() == AccountOperationType.REVERSE_DEBIT).findFirst().orElseThrow();
        assertThat(reversal.reversesOperationId()).isEqualTo(debit.operationId());
        assertThat(reversal.operationId()).isNotEqualTo(debit.operationId());
        verify(ledger, never()).reverse(any());
        assertThat(historyOf(t.getId())).containsExactly(TransferStatus.INITIATED, TransferStatus.VALIDATING,
                TransferStatus.PROCESSING, TransferStatus.COMPENSATION_REQUIRED, TransferStatus.COMPENSATED);
    }

    @Test
    void creditRejectionReversesLedgerEntryThenDebitInThatOrder() {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenAnswer(inv -> {
            AccountOperationRequest r = inv.getArgument(0);
            return r.type() == AccountOperationType.CREDIT ? RemoteResult.rejected("ACCOUNT_CLOSED")
                    : RemoteResult.succeeded();
        });
        when(ledger.post(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.reverse(any())).thenReturn(RemoteResult.succeeded());

        Transfer t = transferService.initiate(key("creditrej"), cmd("30.00"), customer()).transfer();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
        assertThat(t.getErrorCode()).isEqualTo("DESTINATION_CREDIT_REJECTED");
        InOrder order = inOrder(accounts, ledger);
        order.verify(accounts).applyOperation(argThat(isType(AccountOperationType.DEBIT)));
        order.verify(ledger).post(any());
        order.verify(accounts).applyOperation(argThat(isType(AccountOperationType.CREDIT)));
        order.verify(ledger).reverse(any());
        order.verify(accounts).applyOperation(argThat(isType(AccountOperationType.REVERSE_DEBIT)));
    }

    // ------------------------------------------------------------------ ambiguity / retries

    @Test
    void timeoutOnDebitIsResolvedByStatusLookupWithoutNewOperationIdOrSecondDebit() {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenAnswer(inv -> {
            AccountOperationRequest r = inv.getArgument(0);
            return r.type() == AccountOperationType.DEBIT ? RemoteResult.unknown("IO_ERROR_OR_TIMEOUT")
                    : RemoteResult.succeeded();
        });
        when(accounts.getOperation(any(), any())).thenReturn(RemoteResult.succeeded());
        when(ledger.post(any())).thenReturn(RemoteResult.succeeded());

        Transfer t = transferService.initiate(key("timeout"), cmd("40.00"), customer()).transfer();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(sentOfType(AccountOperationType.DEBIT)).isEqualTo(1);
        UUID debitOpId = operationStore.byType(t.getId()).get(OperationType.DEBIT_SOURCE).getOperationId();
        verify(accounts).getOperation(SRC, debitOpId);
    }

    @Test
    void persistentlyAmbiguousDebitEscalatesToReconciliationNeverFailedAndKeepsOneOperationId() {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.unknown("IO_ERROR_OR_TIMEOUT"));
        when(accounts.getOperation(any(), any())).thenReturn(RemoteResult.unknown("IO_ERROR_OR_TIMEOUT"));

        Transfer t = transferService.initiate(key("ambig"), cmd("40.00"), customer()).transfer();
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PROCESSING);
        processor.process(t.getId());
        processor.process(t.getId());

        Transfer end = reload(t.getId());
        assertThat(end.getStatus()).isEqualTo(TransferStatus.RECONCILIATION_REQUIRED);
        assertThat(end.getErrorCode()).isEqualTo("OUTCOME_UNKNOWN");
        TransferOperation debit = operationStore.byType(t.getId()).get(OperationType.DEBIT_SOURCE);
        assertThat(debit.getStatus()).isEqualTo(OperationStatus.UNKNOWN);
        assertThat(debit.getAttemptCount()).isEqualTo(3);
        assertThat(sentAccountOps().stream().map(AccountOperationRequest::operationId).distinct()).hasSize(1);
        verifyNoInteractions(ledger);

        // the automatic engine must not touch a reconciliation-required transfer
        processor.process(t.getId());
        assertThat(reload(t.getId()).getStatus()).isEqualTo(TransferStatus.RECONCILIATION_REQUIRED);
        assertThat(sentOfType(AccountOperationType.DEBIT)).isEqualTo(3);
    }

    @Test
    void reconciliationUsesRemoteStatusAndThenCompletesTheWorkflow() {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenAnswer(inv -> {
            AccountOperationRequest r = inv.getArgument(0);
            return r.type() == AccountOperationType.DEBIT ? RemoteResult.unknown("IO_ERROR_OR_TIMEOUT")
                    : RemoteResult.succeeded();
        });
        when(accounts.getOperation(any(), any())).thenReturn(RemoteResult.unknown("IO_ERROR_OR_TIMEOUT"));
        when(ledger.post(any())).thenReturn(RemoteResult.succeeded());
        Transfer t = transferService.initiate(key("reconcile"), cmd("40.00"), customer()).transfer();
        processor.process(t.getId());
        processor.process(t.getId());
        assertThat(reload(t.getId()).getStatus()).isEqualTo(TransferStatus.RECONCILIATION_REQUIRED);

        // remote status still unavailable: nothing may change
        Transfer inconclusive = transferService.reconcile(t.getId());
        assertThat(inconclusive.getStatus()).isEqualTo(TransferStatus.RECONCILIATION_REQUIRED);

        // remote confirms the debit really happened
        when(accounts.getOperation(any(), any())).thenReturn(RemoteResult.succeeded());
        Transfer done = transferService.reconcile(t.getId());

        assertThat(done.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(sentOfType(AccountOperationType.DEBIT)).isEqualTo(3);   // no extra debit after reconciliation
        assertThat(historyOf(t.getId())).contains(TransferStatus.RECONCILIATION_REQUIRED)
                .endsWith(TransferStatus.PROCESSING, TransferStatus.COMPLETED);
    }

    @Test
    void reconciliationWhenRemoteNeverSawTheOperationResendsWithTheSameOperationId() {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.unknown("IO_ERROR_OR_TIMEOUT"));
        when(accounts.getOperation(any(), any())).thenReturn(RemoteResult.unknown("IO_ERROR_OR_TIMEOUT"));
        when(ledger.post(any())).thenReturn(RemoteResult.succeeded());
        Transfer t = transferService.initiate(key("notfound"), cmd("40.00"), customer()).transfer();
        processor.process(t.getId());
        processor.process(t.getId());
        UUID debitOpId = operationStore.byType(t.getId()).get(OperationType.DEBIT_SOURCE).getOperationId();

        when(accounts.getOperation(any(), any())).thenReturn(RemoteResult.notFound());
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.succeeded());
        Transfer done = transferService.reconcile(t.getId());

        assertThat(done.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(sentAccountOps().stream().map(AccountOperationRequest::operationId)
                .filter(debitOpId::equals).count()).isEqualTo(4);   // 3 ambiguous + 1 resend, all with the same id
    }

    @Test
    void retryableLedgerFailureResumesWithoutRepeatingTheDebit() {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.post(any())).thenReturn(RemoteResult.retryable("SERVICE_UNAVAILABLE"))
                .thenReturn(RemoteResult.succeeded());

        Transfer t = transferService.initiate(key("resume"), cmd("15.00"), customer()).transfer();
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PROCESSING);

        Transfer resumed = transferService.retry(t.getId());

        assertThat(resumed.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(sentOfType(AccountOperationType.DEBIT)).isEqualTo(1);
        ArgumentCaptor<LedgerPostRequest> captor = ArgumentCaptor.forClass(LedgerPostRequest.class);
        verify(ledger, times(2)).post(captor.capture());
        assertThat(captor.getAllValues().get(0).idempotencyKey()).isEqualTo(captor.getAllValues().get(1).idempotencyKey());
    }

    @Test
    void retryIsRejectedForTerminalAndReconciliationTransfers() {
        stubEverythingSucceeds();
        Transfer done = transferService.initiate(key("terminal"), cmd("15.00"), customer()).transfer();
        assertThatThrownBy(() -> transferService.retry(done.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_STATE"));
        assertThatThrownBy(() -> transferService.reconcile(done.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_STATE"));
    }

    @Test
    void ledgerNeverAvailableMeansProvablyNotApplied_soDebitIsReversedNotLeftHanging() {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.post(any())).thenReturn(RemoteResult.retryable("SERVICE_UNAVAILABLE"));

        Transfer t = transferService.initiate(key("ledgerdown"), cmd("15.00"), customer()).transfer();
        processor.process(t.getId());
        processor.process(t.getId());

        Transfer end = reload(t.getId());
        assertThat(end.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
        assertThat(sentOfType(AccountOperationType.REVERSE_DEBIT)).isEqualTo(1);
        verify(ledger, times(3)).post(any());
    }

    @Test
    void debitServiceNeverReachableFailsSafelyBecauseNothingCouldHaveMoved() {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.retryable("CONNECTION_FAILED"));

        Transfer t = transferService.initiate(key("debitdown"), cmd("15.00"), customer()).transfer();
        processor.process(t.getId());
        processor.process(t.getId());

        Transfer end = reload(t.getId());
        assertThat(end.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(end.getErrorCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void failedCompensationEscalatesToReconciliationInsteadOfPretendingSuccess() {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenAnswer(inv -> {
            AccountOperationRequest r = inv.getArgument(0);
            return r.type() == AccountOperationType.REVERSE_DEBIT ? RemoteResult.rejected("ACCOUNT_CLOSED")
                    : RemoteResult.succeeded();
        });
        when(ledger.post(any())).thenReturn(RemoteResult.rejected("UNBALANCED_ENTRY"));

        Transfer t = transferService.initiate(key("compfail"), cmd("15.00"), customer()).transfer();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.RECONCILIATION_REQUIRED);
        assertThat(t.getErrorCode()).isEqualTo("COMPENSATION_FAILED");
        // a definitive compensation rejection cannot be auto-resolved by reconcile
        assertThat(transferService.reconcile(t.getId()).getStatus()).isEqualTo(TransferStatus.RECONCILIATION_REQUIRED);
    }

    // ------------------------------------------------------------------ crash recovery & leases

    private Transfer crashedProcessingTransferWithDebitDone(String key) {
        Instant now = Instant.now();
        Transfer candidate = Transfer.initiate(UUID.randomUUID(), key, "fp-" + key, SRC, DST,
                new java.math.BigDecimal("60.00"), "INR", "user-1", CUSTOMER, now);
        Transfer saved = store.create(candidate);
        store.transition(saved.getId(), TransferStatus.VALIDATING, "VALIDATION_STARTED", null);
        store.transition(saved.getId(), TransferStatus.PROCESSING, "VALIDATION_PASSED", null);
        TransferOperation debit = operationStore.ensure(saved.getId(), OperationType.DEBIT_SOURCE);
        operationStore.beginAttempt(debit.getOperationId());
        operationStore.markFinal(debit.getOperationId(), OperationStatus.SUCCEEDED, "OK");
        return saved;
    }

    @Test
    void recoveryResumesCrashedTransferWithoutRepeatingCompletedDebit() {
        stubEverythingSucceeds();
        Transfer t = crashedProcessingTransferWithDebitDone("test-key-crash-1");
        Instant now = Instant.now();
        // a worker died holding the lease; the lease has expired
        assertThat(store.tryAcquireLease(t.getId(), "dead-worker", now.minusSeconds(300), now.minusSeconds(180))).isTrue();

        int picked = recovery.recoverBatch();

        assertThat(picked).isEqualTo(1);
        assertThat(reload(t.getId()).getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(sentOfType(AccountOperationType.DEBIT)).isZero();
        assertThat(sentOfType(AccountOperationType.CREDIT)).isEqualTo(1);
        verify(ledger, times(1)).post(any());
    }

    @Test
    void recoverySkipsTransfersOwnedByALiveLeaseAndLeavesTerminalOnesAlone() {
        stubEverythingSucceeds();
        Transfer t = crashedProcessingTransferWithDebitDone("test-key-live-1");
        assertThat(store.tryAcquireLease(t.getId(), "live-worker", Instant.now(), Instant.now().plusSeconds(120))).isTrue();

        assertThat(recovery.recoverBatch()).isZero();
        assertThat(reload(t.getId()).getStatus()).isEqualTo(TransferStatus.PROCESSING);
        verify(ledger, never()).post(any());

        Transfer done = transferService.initiate(key("recoverterminal"), cmd("5.00"), customer()).transfer();
        assertThat(done.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(store.findRecoverable(50)).doesNotContain(done.getId());
    }

    @Test
    void leaseIsExclusiveUntilItExpires() {
        stubEverythingSucceeds();
        Transfer t = crashedProcessingTransferWithDebitDone("test-key-lease-1");
        Instant now = Instant.now();

        assertThat(store.tryAcquireLease(t.getId(), "a", now, now.plusSeconds(60))).isTrue();
        assertThat(store.tryAcquireLease(t.getId(), "b", now.plusSeconds(1), now.plusSeconds(61))).isFalse();
        store.releaseLease(t.getId(), "someone-else");   // wrong owner cannot release
        assertThat(store.tryAcquireLease(t.getId(), "b", now.plusSeconds(2), now.plusSeconds(62))).isFalse();
        store.releaseLease(t.getId(), "a");
        assertThat(store.tryAcquireLease(t.getId(), "b", now.plusSeconds(3), now.plusSeconds(63))).isTrue();
    }

    // ------------------------------------------------------------------ persistence guarantees

    @Test
    void databaseEnforcesIdempotencyKeyOperationUniquenessAndMoneyRules() {
        stubEverythingSucceeds();
        Transfer t = transferService.initiate(key("db"), cmd("5.00"), customer()).transfer();
        Instant now = Instant.now();

        Transfer dupKey = Transfer.initiate(UUID.randomUUID(), t.getIdempotencyKey(), "other", SRC, DST,
                new java.math.BigDecimal("1.00"), "INR", "u", CUSTOMER, now);
        assertThatThrownBy(() -> transferRepo.saveAndFlush(dupKey)).isInstanceOf(DataIntegrityViolationException.class);

        Transfer sameAccounts = Transfer.initiate(UUID.randomUUID(), "test-key-db-same", "f", SRC, SRC,
                new java.math.BigDecimal("1.00"), "INR", "u", CUSTOMER, now);
        assertThatThrownBy(() -> transferRepo.saveAndFlush(sameAccounts)).isInstanceOf(DataIntegrityViolationException.class);

        Transfer zero = Transfer.initiate(UUID.randomUUID(), "test-key-db-zero", "f", SRC, DST,
                java.math.BigDecimal.ZERO, "INR", "u", CUSTOMER, now);
        assertThatThrownBy(() -> transferRepo.saveAndFlush(zero)).isInstanceOf(DataIntegrityViolationException.class);

        TransferOperation dupOp = TransferOperation.create(t.getId(), OperationType.DEBIT_SOURCE, now);
        assertThatThrownBy(() -> operationRepo.saveAndFlush(dupOp)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(transferRepo.count()).isEqualTo(1);
        assertThat(operationRepo.count()).isEqualTo(3);
    }

    @Test
    void illegalTransitionIsRejectedAndLeavesStateAndHistoryUntouched() {
        stubEverythingSucceeds();
        Transfer done = transferService.initiate(key("illegal"), cmd("5.00"), customer()).transfer();
        int historyBefore = historyRepo.findByTransferIdOrderByIdAsc(done.getId()).size();

        assertThatThrownBy(() -> store.transition(done.getId(), TransferStatus.PROCESSING, "HACK", null))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(reload(done.getId()).getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(historyRepo.findByTransferIdOrderByIdAsc(done.getId())).hasSize(historyBefore);
    }

    @Test
    void storedErrorsNeverContainRemoteText() {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.rejected("SOME_REMOTE_CODE"));

        Transfer t = transferService.initiate(key("safeerr"), cmd("5.00"), customer()).transfer();

        assertThat(t.getErrorCode()).isEqualTo("SOME_REMOTE_CODE");
        assertThat(t.getErrorMessage()).isEqualTo("The operation was rejected by a downstream service.");
    }
}
