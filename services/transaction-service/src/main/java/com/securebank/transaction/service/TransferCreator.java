package com.securebank.transaction.service;

import com.securebank.transaction.domain.Transfer;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Race-safe "create or fetch by idempotency key". Deliberately NOT transactional itself: if two requests race,
 * the database unique constraint rejects the loser's insert (its transaction rolls back inside TransferStore),
 * and the loser then re-reads the winner's row in a fresh transaction.
 */
@Component
public class TransferCreator {

    public record Result(Transfer transfer, boolean created) {
    }

    private final TransferStore store;

    public TransferCreator(TransferStore store) {
        this.store = store;
    }

    public Result createOrGet(Transfer candidate) {
        Optional<Transfer> existing = store.findByKey(candidate.getIdempotencyKey());
        if (existing.isPresent()) {
            return new Result(existing.get(), false);
        }
        try {
            return new Result(store.create(candidate), true);
        } catch (DataIntegrityViolationException e) {
            return store.findByKey(candidate.getIdempotencyKey())
                    .map(t -> new Result(t, false))
                    .orElseThrow(() -> e);
        }
    }
}
