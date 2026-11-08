package com.securebank.transaction.workflow;

import com.securebank.transaction.client.AccountServiceClient;
import com.securebank.transaction.client.AccountView;
import com.securebank.transaction.client.RemoteCallException;
import com.securebank.transaction.config.TransferProperties;
import com.securebank.transaction.domain.OperationStatus;
import com.securebank.transaction.domain.OperationType;
import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferOperation;
import com.securebank.transaction.domain.TransferStatus;
import com.securebank.transaction.domain.TransferType;
import com.securebank.transaction.service.OperationStore;
import com.securebank.transaction.service.TransferStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The saga engine. It is a resumable, state-driven loop: every iteration reads the persisted state, performs at
 * most one bounded piece of work, and persists the result. Nothing important lives in memory, so a crash at any
 * point leaves the transfer resumable by this same method.
 *
 * <pre>
 * Funding path: validate -> LEDGER_POST -> CREDIT_DESTINATION -> COMPLETED (no debit leg, ledger-only contra)
 * Forward path : validate -> DEBIT_SOURCE -> LEDGER_POST -> CREDIT_DESTINATION -> COMPLETED
 * Compensation : (LEDGER_REVERSAL if the ledger entry was posted) -> REVERSE_DEBIT -> COMPENSATED
 * </pre>
 *
 * A transfer is COMPLETED only when all three forward steps are definitively SUCCEEDED.
 * Concurrency: a database lease ensures only one worker advances a given transfer at a time.
 */
@Component
public class TransferProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransferProcessor.class);
    private static final int MAX_ITERATIONS = 12;

    private final TransferStore store;
    private final OperationStore ops;
    private final StepExecutor steps;
    private final AccountServiceClient accounts;
    private final TransferProperties props;
    private final Clock clock;

    public TransferProcessor(TransferStore store, OperationStore ops, StepExecutor steps,
                             AccountServiceClient accounts, TransferProperties props, Clock clock) {
        this.store = store;
        this.ops = ops;
        this.steps = steps;
        this.accounts = accounts;
        this.props = props;
        this.clock = clock;
    }

    /** Advances the transfer as far as possible. Safe to call repeatedly and concurrently. */
    public void process(UUID transferId) {
        String owner = UUID.randomUUID().toString();
        Instant now = clock.instant();
        if (!store.tryAcquireLease(transferId, owner, now, now.plus(props.leaseDuration()))) {
            log.debug("transfer {} is leased by another worker; skipping", transferId);
            return;
        }
        try {
            for (int i = 0; i < MAX_ITERATIONS; i++) {
                Transfer t = store.require(transferId);
                TransferStatus before = t.getStatus();
                switch (before) {
                    case INITIATED:
                        store.transition(transferId, TransferStatus.VALIDATING, "VALIDATION_STARTED", null);
                        break;
                    case VALIDATING:
                        validate(t);
                        break;
                    case PROCESSING:
                        runForward(t);
                        break;
                    case COMPENSATION_REQUIRED:
                        runCompensation(t);
                        break;
                    default:
                        return;   // terminal, or RECONCILIATION_REQUIRED (needs explicit reconciliation)
                }
                if (store.require(transferId).getStatus() == before) {
                    return;       // waiting for a scheduled retry
                }
            }
        } finally {
            store.releaseLease(transferId, owner);
        }
    }

    // ------------------------------------------------------------------ validation

    private void validate(Transfer t) {
        try {
            Optional<AccountView> src = t.getTransferType() == TransferType.FUNDING
                    ? Optional.empty() : accounts.findAccount(t.getSourceAccountId());
            Optional<AccountView> dst = accounts.findAccount(t.getDestinationAccountId());
            String rejection = check(t, src, dst);
            if (rejection != null) {
                store.transition(t.getId(), TransferStatus.FAILED, "VALIDATION_REJECTED", rejection);
            } else {
                store.transition(t.getId(), TransferStatus.PROCESSING, "VALIDATION_PASSED", null);
            }
        } catch (RemoteCallException e) {
            // Read-only calls only: no money can have moved, so exhausting retries safely ends in FAILED.
            int attempts = store.recordValidationAttempt(t.getId());
            if (attempts >= props.maxAttempts()) {
                store.transition(t.getId(), TransferStatus.FAILED, "VALIDATION_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE");
            } else {
                store.scheduleRetry(t.getId(), clock.instant().plus(Backoff.delay(props, attempts)));
            }
        }
    }

    private String check(Transfer t, Optional<AccountView> src, Optional<AccountView> dst) {
        if (t.getTransferType() == TransferType.FUNDING) {
            return checkFunding(t, dst);
        }
        if (src.isEmpty()) {
            return "SOURCE_ACCOUNT_NOT_FOUND";
        }
        AccountView s = src.get();
        if (s.customerId() == null || !s.customerId().equals(t.getInitiatorCustomerId())) {
            return "SOURCE_ACCOUNT_NOT_FOUND";   // never reveal that the account exists but is someone else's
        }
        if (dst.isEmpty()) {
            return "DESTINATION_ACCOUNT_NOT_FOUND";
        }
        AccountView d = dst.get();
        if (!s.isActive()) {
            return "SOURCE_ACCOUNT_INELIGIBLE";
        }
        if (!d.isActive()) {
            return "DESTINATION_ACCOUNT_INELIGIBLE";
        }
        if (!t.getCurrency().equalsIgnoreCase(s.currency()) || !t.getCurrency().equalsIgnoreCase(d.currency())) {
            return "CURRENCY_MISMATCH";
        }
        return null;
    }

    /**
     * Funding (D7): the target must exist, be ACTIVE (PENDING, REJECTED, FROZEN and CLOSED accounts are not), match
     * the currency and not be owned by the funding administrator. Rejection happens before any ledger or balance step.
     */
    private String checkFunding(Transfer t, Optional<AccountView> dst) {
        if (dst.isEmpty()) {
            return "FUNDING_TARGET_NOT_FOUND";
        }
        AccountView d = dst.get();
        if (!d.isActive()) {
            return "FUNDING_TARGET_INELIGIBLE";
        }
        String adminCustomerId = t.getFundingActorCustomerId();
        if (d.customerId() != null && adminCustomerId != null && d.customerId().equals(adminCustomerId)) {
            return "FUNDING_OWN_ACCOUNT";
        }
        if (!t.getCurrency().equalsIgnoreCase(d.currency())) {
            return "CURRENCY_MISMATCH";
        }
        return null;
    }

    // ------------------------------------------------------------------ forward path

    private void runForward(Transfer t) {
        // Funding has no debit leg: the ledger entry against the contra reference is the first and only source side.
        if (t.getTransferType() != TransferType.FUNDING && !forwardStep(t, OperationType.DEBIT_SOURCE)) {
            return;
        }
        if (!forwardStep(t, OperationType.LEDGER_POST)) {
            return;
        }
        if (!forwardStep(t, OperationType.CREDIT_DESTINATION)) {
            return;
        }
        // The precise completion point: debit, ledger posting AND credit are all definitively SUCCEEDED.
        store.transition(t.getId(), TransferStatus.COMPLETED, "ALL_STEPS_SUCCEEDED", null);
    }

    /** @return true if the step succeeded and the flow may continue; false if the flow must stop now */
    private boolean forwardStep(Transfer t, OperationType type) {
        TransferOperation op = ops.ensure(t.getId(), type);
        Map<OperationType, TransferOperation> all = ops.byType(t.getId());
        StepResult r = steps.run(t, op, all);
        switch (r.outcome()) {
            case SUCCEEDED:
                return true;
            case REJECTED:
                onForwardRejected(t, type, r.code());
                return false;
            case RETRY_LATER:
                store.scheduleRetry(t.getId(), clock.instant().plus(Backoff.delay(props, r.attempts())));
                return false;
            case EXHAUSTED_AMBIGUOUS:
                store.transition(t.getId(), TransferStatus.RECONCILIATION_REQUIRED,
                        type.name() + "_OUTCOME_UNKNOWN", "OUTCOME_UNKNOWN");
                return false;
            case EXHAUSTED_NOT_APPLIED:
            default:
                onForwardRejected(t, type, "DEPENDENCY_UNAVAILABLE");
                return false;
        }
    }

    private void onForwardRejected(Transfer t, OperationType type, String code) {
        if (type == OperationType.LEDGER_POST && t.getTransferType() == TransferType.FUNDING) {
            // Nothing posted and nothing credited: the funding event simply fails with no money movement.
            store.transition(t.getId(), TransferStatus.FAILED, "LEDGER_POSTING_REJECTED", "LEDGER_POSTING_REJECTED");
        } else if (type == OperationType.DEBIT_SOURCE) {
            // Nothing has moved: a definitive debit rejection (e.g. INSUFFICIENT_FUNDS) ends the transfer.
            store.transition(t.getId(), TransferStatus.FAILED, "DEBIT_REJECTED", code);
        } else {
            String failure = type == OperationType.LEDGER_POST ? "LEDGER_POSTING_REJECTED"
                    : "DESTINATION_CREDIT_REJECTED";
            store.transition(t.getId(), TransferStatus.COMPENSATION_REQUIRED, type.name() + "_REJECTED", failure);
        }
    }

    // ------------------------------------------------------------------ compensation

    private void runCompensation(Transfer t) {
        Map<OperationType, TransferOperation> all = ops.byType(t.getId());
        boolean funding = t.getTransferType() == TransferType.FUNDING;
        TransferOperation debit = all.get(OperationType.DEBIT_SOURCE);
        if (!funding && (debit == null || debit.getStatus() != OperationStatus.SUCCEEDED)) {
            store.transition(t.getId(), TransferStatus.RECONCILIATION_REQUIRED, "COMPENSATION_PRECONDITION_FAILED",
                    "COMPENSATION_FAILED");
            return;
        }
        TransferOperation ledgerPost = all.get(OperationType.LEDGER_POST);
        if (ledgerPost != null && ledgerPost.getStatus() == OperationStatus.SUCCEEDED) {
            if (!compensationStep(t, OperationType.LEDGER_REVERSAL)) {
                return;
            }
        }
        if (!funding && !compensationStep(t, OperationType.REVERSE_DEBIT)) {
            return;
        }
        store.transition(t.getId(), TransferStatus.COMPENSATED, "COMPENSATION_COMPLETED", null);
    }

    private boolean compensationStep(Transfer t, OperationType type) {
        TransferOperation op = ops.ensure(t.getId(), type);
        Map<OperationType, TransferOperation> all = ops.byType(t.getId());
        StepResult r = steps.run(t, op, all);
        switch (r.outcome()) {
            case SUCCEEDED:
                return true;
            case RETRY_LATER:
                store.scheduleRetry(t.getId(), clock.instant().plus(Backoff.delay(props, r.attempts())));
                return false;
            default:
                // Rejected or exhausted: compensation cannot be guaranteed. Never guess; hand to reconciliation.
                store.transition(t.getId(), TransferStatus.RECONCILIATION_REQUIRED,
                        type.name() + "_NOT_COMPLETED", "COMPENSATION_FAILED");
                return false;
        }
    }
}
