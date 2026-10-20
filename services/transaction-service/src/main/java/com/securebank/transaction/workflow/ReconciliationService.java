package com.securebank.transaction.workflow;

import com.securebank.transaction.config.TransferProperties;
import com.securebank.transaction.domain.OperationStatus;
import com.securebank.transaction.domain.OperationType;
import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferOperation;
import com.securebank.transaction.domain.TransferStatus;
import com.securebank.transaction.exception.ApiException;
import com.securebank.transaction.service.OperationStore;
import com.securebank.transaction.service.TransferStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Explicit, operator-triggered resolution of RECONCILIATION_REQUIRED transfers. It re-queries the remote status
 * of every unsettled step, persists any definitive answer, and then moves the transfer back onto the forward path
 * or the compensation path. It never invents an outcome: if a status lookup is inconclusive, nothing changes.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final TransferStore store;
    private final OperationStore ops;
    private final StepExecutor steps;
    private final TransferProcessor processor;
    private final TransferProperties props;
    private final Clock clock;

    public ReconciliationService(TransferStore store, OperationStore ops, StepExecutor steps,
                                 TransferProcessor processor, TransferProperties props, Clock clock) {
        this.store = store;
        this.ops = ops;
        this.steps = steps;
        this.processor = processor;
        this.props = props;
        this.clock = clock;
    }

    public Transfer reconcile(UUID transferId) {
        String owner = UUID.randomUUID().toString();
        Instant now = clock.instant();
        if (!store.tryAcquireLease(transferId, owner, now, now.plus(props.leaseDuration()))) {
            throw ApiException.conflict("TRANSFER_BUSY", "The transfer is currently being processed.");
        }
        boolean resumed = false;
        try {
            Transfer t = store.require(transferId);
            if (t.getStatus() != TransferStatus.RECONCILIATION_REQUIRED) {
                throw ApiException.conflict("INVALID_STATE",
                        "Reconciliation applies only to transfers in RECONCILIATION_REQUIRED.");
            }
            Map<OperationType, TransferOperation> byType = ops.byType(transferId);
            boolean inconclusive = false;
            for (TransferOperation op : byType.values()) {
                if (!op.getStatus().isFinal() && steps.refresh(t, op) == StepExecutor.Refresh.INCONCLUSIVE) {
                    inconclusive = true;
                }
            }
            if (inconclusive) {
                log.info("transfer {} reconciliation inconclusive; remote status unavailable", transferId);
                return store.require(transferId);
            }

            byType = ops.byType(transferId);
            Decision decision = decide(byType);
            if (decision.target == null) {
                log.warn("transfer {} needs manual intervention: a compensating step was definitively rejected",
                        transferId);
                return store.require(transferId);
            }
            ops.resetAttempts(transferId);
            store.transition(transferId, decision.target, "RECONCILED", decision.errorCode);
            resumed = decision.target.isRecoverable();
        } finally {
            store.releaseLease(transferId, owner);
        }
        if (resumed) {
            processor.process(transferId);   // continue forward or compensating work (re-sends use SAME ids)
        }
        return store.require(transferId);
    }

    private record Decision(TransferStatus target, String errorCode) {
    }

    private static Decision decide(Map<OperationType, TransferOperation> byType) {
        TransferOperation reverseLedger = byType.get(OperationType.LEDGER_REVERSAL);
        TransferOperation reverseDebit = byType.get(OperationType.REVERSE_DEBIT);
        if (reverseLedger != null || reverseDebit != null) {
            if (isRejected(reverseLedger) || isRejected(reverseDebit)) {
                return new Decision(null, null);   // cannot be fixed automatically
            }
            return new Decision(TransferStatus.COMPENSATION_REQUIRED, null);
        }
        TransferOperation debit = byType.get(OperationType.DEBIT_SOURCE);
        if (isRejected(debit)) {
            return new Decision(TransferStatus.FAILED, "DEBIT_REJECTED");
        }
        if (isRejected(byType.get(OperationType.LEDGER_POST))) {
            return new Decision(TransferStatus.COMPENSATION_REQUIRED, "LEDGER_POSTING_REJECTED");
        }
        if (isRejected(byType.get(OperationType.CREDIT_DESTINATION))) {
            return new Decision(TransferStatus.COMPENSATION_REQUIRED, "DESTINATION_CREDIT_REJECTED");
        }
        return new Decision(TransferStatus.PROCESSING, null);
    }

    private static boolean isRejected(TransferOperation op) {
        return op != null && op.getStatus() == OperationStatus.REJECTED;
    }
}
