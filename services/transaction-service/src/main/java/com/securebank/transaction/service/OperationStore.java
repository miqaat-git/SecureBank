package com.securebank.transaction.service;

import com.securebank.transaction.domain.OperationStatus;
import com.securebank.transaction.domain.OperationType;
import com.securebank.transaction.domain.TransferOperation;
import com.securebank.transaction.repository.TransferOperationRepository;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable step records. The operation id is created once, committed, and reused for every retry. */
@Service
public class OperationStore {

    private final TransferOperationRepository operations;
    private final Clock clock;

    public OperationStore(TransferOperationRepository operations, Clock clock) {
        this.operations = operations;
        this.clock = clock;
    }

    /** Returns the existing step record or creates it (committed BEFORE any remote call is made). */
    @Transactional
    public TransferOperation ensure(UUID transferId, OperationType type) {
        return operations.findByTransferIdAndOperationType(transferId, type)
                .orElseGet(() -> operations.saveAndFlush(TransferOperation.create(transferId, type, clock.instant())));
    }

    @Transactional(readOnly = true)
    public TransferOperation get(UUID operationId) {
        return operations.findById(operationId).orElseThrow();
    }

    /** Persists the incremented attempt counter before the remote call is made. */
    @Transactional
    public TransferOperation beginAttempt(UUID operationId) {
        TransferOperation op = operations.findById(operationId).orElseThrow();
        op.beginAttempt(clock.instant());
        return operations.saveAndFlush(op);
    }

    @Transactional
    public TransferOperation markFinal(UUID operationId, OperationStatus status, String code) {
        TransferOperation op = operations.findById(operationId).orElseThrow();
        op.markFinal(status, code, clock.instant());
        return operations.saveAndFlush(op);
    }

    @Transactional
    public TransferOperation markInconclusive(UUID operationId, boolean ambiguous, String code) {
        TransferOperation op = operations.findById(operationId).orElseThrow();
        op.markInconclusive(ambiguous, code, clock.instant());
        return operations.saveAndFlush(op);
    }

    @Transactional(readOnly = true)
    public List<TransferOperation> list(UUID transferId) {
        return operations.findByTransferIdOrderByCreatedAtAsc(transferId);
    }

    @Transactional(readOnly = true)
    public Map<OperationType, TransferOperation> byType(UUID transferId) {
        Map<OperationType, TransferOperation> map = new EnumMap<>(OperationType.class);
        for (TransferOperation op : operations.findByTransferIdOrderByCreatedAtAsc(transferId)) {
            map.put(op.getOperationType(), op);
        }
        return map;
    }

    @Transactional
    public void resetAttempts(UUID transferId) {
        for (TransferOperation op : operations.findByTransferIdOrderByCreatedAtAsc(transferId)) {
            op.resetAttempts(clock.instant());
            operations.save(op);
        }
    }
}
