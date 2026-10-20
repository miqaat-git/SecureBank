package com.securebank.transaction.service;

import com.securebank.transaction.domain.FailureMessages;
import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferHistory;
import com.securebank.transaction.domain.TransferStatus;
import com.securebank.transaction.exception.ApiException;
import com.securebank.transaction.security.Caller;
import com.securebank.transaction.repository.TransferHistoryRepository;
import com.securebank.transaction.repository.TransferRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short, self-contained database transactions for transfers. No remote call ever happens inside one of these
 * transactions. Every state change and its history row commit atomically.
 */
@Service
public class TransferStore {

    private static final Logger log = LoggerFactory.getLogger(TransferStore.class);

    private final TransferRepository transfers;
    private final TransferHistoryRepository history;
    private final Clock clock;

    public TransferStore(TransferRepository transfers, TransferHistoryRepository history, Clock clock) {
        this.transfers = transfers;
        this.history = history;
        this.clock = clock;
    }

    /** Inserts the transfer and its first history row. Throws DataIntegrityViolationException on key clash. */
    @Transactional
    public Transfer create(Transfer candidate) {
        Transfer saved = transfers.saveAndFlush(candidate);
        history.save(new TransferHistory(saved.getId(), null, TransferStatus.INITIATED, "TRANSFER_CREATED", null,
                clock.instant()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<Transfer> find(UUID id) {
        return transfers.findById(id);
    }

    public Transfer require(UUID id) {
        return find(id).orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer not found."));
    }

    @Transactional(readOnly = true)
    public Optional<Transfer> findByKey(String idempotencyKey) {
        return transfers.findByIdempotencyKey(idempotencyKey);
    }

    /** Applies a state-machine-checked transition and records history in the same transaction. */
    @Transactional
    public Transfer transition(UUID id, TransferStatus target, String eventCode, String errorCode) {
        Transfer t = transfers.findById(id)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer not found."));
        TransferStatus previous = t.getStatus();
        Instant now = clock.instant();
        t.transitionTo(target, errorCode, now);   // throws IllegalStateTransitionException if not allowed
        transfers.save(t);
        history.save(new TransferHistory(id, previous, target, eventCode, FailureMessages.describe(errorCode), now));
        log.info("transfer {} {} -> {} ({})", id, previous, target, eventCode);
        return t;
    }

    @Transactional
    public int recordValidationAttempt(UUID id) {
        Transfer t = transfers.findById(id)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer not found."));
        int attempts = t.incrementValidationAttempts(clock.instant());
        transfers.save(t);
        return attempts;
    }

    @Transactional
    public void scheduleRetry(UUID id, Instant at) {
        transfers.scheduleNextAttempt(id, at, TransferStatus.recoverableStatuses());
    }

    @Transactional
    public boolean tryAcquireLease(UUID id, String owner, Instant now, Instant expiry) {
        return transfers.acquireLease(id, owner, now, expiry) == 1;
    }

    @Transactional
    public void releaseLease(UUID id, String owner) {
        transfers.releaseLease(id, owner);
    }

    @Transactional(readOnly = true)
    public List<UUID> findRecoverable(int limit) {
        return transfers.findRecoverableIds(TransferStatus.recoverableStatuses(), clock.instant(),
                PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public Page<Transfer> search(TransferSearchCriteria criteria, Caller caller, Pageable pageable) {
        return transfers.findAll(TransferSpecifications.from(criteria, caller), pageable);
    }

    @Transactional(readOnly = true)
    public List<TransferHistory> history(UUID transferId) {
        return history.findByTransferIdOrderByIdAsc(transferId);
    }
}
