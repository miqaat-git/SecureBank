package com.securebank.transaction.workflow;

import com.securebank.transaction.client.RemoteResult;
import com.securebank.transaction.config.TransferProperties;
import com.securebank.transaction.domain.OperationStatus;
import com.securebank.transaction.domain.OperationType;
import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferOperation;
import com.securebank.transaction.service.OperationStore;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes ONE attempt of ONE persisted step with the safety rules of the saga:
 * <ol>
 *   <li>the attempt counter is committed before the remote call;</li>
 *   <li>the same operation id is used for every attempt;</li>
 *   <li>an ambiguous outcome (timeout etc.) makes the step UNKNOWN; the next cycle first queries the remote
 *       status, and only then re-sends with the same id (the remote service deduplicates by that id);</li>
 *   <li>only a definitive remote answer (SUCCEEDED / REJECTED) settles the step.</li>
 * </ol>
 */
@Component
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    public enum Refresh { RESOLVED, NOT_FOUND, INCONCLUSIVE }

    private final OperationStore ops;
    private final RemoteOperations remote;
    private final TransferProperties props;

    public StepExecutor(OperationStore ops, RemoteOperations remote, TransferProperties props) {
        this.ops = ops;
        this.remote = remote;
        this.props = props;
    }

    public StepResult run(Transfer t, TransferOperation op, Map<OperationType, TransferOperation> all) {
        if (op.getStatus() == OperationStatus.SUCCEEDED) {
            return new StepResult(StepResult.Outcome.SUCCEEDED, op.getResultCode(), op.getAttemptCount());
        }
        if (op.getStatus() == OperationStatus.REJECTED) {
            return new StepResult(StepResult.Outcome.REJECTED, op.getResultCode(), op.getAttemptCount());
        }
        if (op.getStatus() == OperationStatus.UNKNOWN) {
            Optional<StepResult> resolved = resolveByLookup(t, op);
            if (resolved.isPresent()) {
                return resolved.get();
            }
        }

        TransferOperation attempt = ops.beginAttempt(op.getOperationId());   // committed BEFORE the call
        RemoteResult result = safeSend(t, attempt, all);
        switch (result.outcome()) {
            case SUCCEEDED:
                return settle(attempt, OperationStatus.SUCCEEDED, StepResult.Outcome.SUCCEEDED, result.code());
            case REJECTED:
                return settle(attempt, OperationStatus.REJECTED, StepResult.Outcome.REJECTED, result.code());
            case RETRYABLE_FAILURE:
                ops.markInconclusive(attempt.getOperationId(), false, result.code());
                break;
            default:
                ops.markInconclusive(attempt.getOperationId(), true, result.code());
                Optional<StepResult> resolved = resolveByLookup(t, attempt);
                if (resolved.isPresent()) {
                    return resolved.get();
                }
                break;
        }

        TransferOperation latest = ops.get(attempt.getOperationId());
        if (latest.getAttemptCount() >= props.maxAttempts()) {
            StepResult.Outcome exhausted = latest.getStatus() == OperationStatus.UNKNOWN
                    ? StepResult.Outcome.EXHAUSTED_AMBIGUOUS
                    : StepResult.Outcome.EXHAUSTED_NOT_APPLIED;
            return new StepResult(exhausted, latest.getResultCode(), latest.getAttemptCount());
        }
        return new StepResult(StepResult.Outcome.RETRY_LATER, latest.getResultCode(), latest.getAttemptCount());
    }

    /** Used by explicit reconciliation: query the remote status of an unsettled step and persist if definitive. */
    public Refresh refresh(Transfer t, TransferOperation op) {
        if (op.getStatus().isFinal()) {
            return Refresh.RESOLVED;
        }
        RemoteResult r = safeLookup(t, op);
        switch (r.outcome()) {
            case SUCCEEDED:
                ops.markFinal(op.getOperationId(), OperationStatus.SUCCEEDED, r.code());
                return Refresh.RESOLVED;
            case REJECTED:
                ops.markFinal(op.getOperationId(), OperationStatus.REJECTED, r.code());
                return Refresh.RESOLVED;
            case NOT_FOUND:
                return Refresh.NOT_FOUND;
            default:
                return Refresh.INCONCLUSIVE;
        }
    }

    private Optional<StepResult> resolveByLookup(Transfer t, TransferOperation op) {
        RemoteResult r = safeLookup(t, op);
        switch (r.outcome()) {
            case SUCCEEDED:
                return Optional.of(settle(op, OperationStatus.SUCCEEDED, StepResult.Outcome.SUCCEEDED, r.code()));
            case REJECTED:
                return Optional.of(settle(op, OperationStatus.REJECTED, StepResult.Outcome.REJECTED, r.code()));
            default:
                return Optional.empty();   // NOT_FOUND or inconclusive: caller re-sends with the same id
        }
    }

    private StepResult settle(TransferOperation op, OperationStatus status, StepResult.Outcome outcome, String code) {
        TransferOperation saved = ops.markFinal(op.getOperationId(), status, code);
        return new StepResult(outcome, saved.getResultCode(), saved.getAttemptCount());
    }

    private RemoteResult safeSend(Transfer t, TransferOperation op, Map<OperationType, TransferOperation> all) {
        try {
            RemoteResult r = remote.send(t, op, all);
            return r != null ? r : RemoteResult.unknown("CLIENT_ERROR");
        } catch (RuntimeException e) {
            log.warn("transfer {} step {} send failed unexpectedly: {}", t.getId(), op.getOperationType(),
                    e.getClass().getSimpleName());
            return RemoteResult.unknown("CLIENT_ERROR");   // conservative: outcome unknown
        }
    }

    private RemoteResult safeLookup(Transfer t, TransferOperation op) {
        try {
            RemoteResult r = remote.lookup(t, op);
            return r != null ? r : RemoteResult.unknown("CLIENT_ERROR");
        } catch (RuntimeException e) {
            log.warn("transfer {} step {} lookup failed unexpectedly: {}", t.getId(), op.getOperationType(),
                    e.getClass().getSimpleName());
            return RemoteResult.unknown("CLIENT_ERROR");
        }
    }
}
