package com.securebank.transaction.service;

import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferHistory;
import com.securebank.transaction.domain.TransferOperation;
import com.securebank.transaction.exception.ApiException;
import com.securebank.transaction.security.Caller;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** Read side with ownership enforcement. Inaccessible transfers look identical to non-existent ones (404). */
@Service
public class TransferQueryService {

    private final TransferStore store;
    private final OperationStore operations;

    public TransferQueryService(TransferStore store, OperationStore operations) {
        this.store = store;
        this.operations = operations;
    }

    public Transfer get(UUID id, Caller caller) {
        return store.find(id).filter(t -> canView(t, caller)).orElseThrow(TransferQueryService::notFound);
    }

    public Transfer getByKey(String idempotencyKey, Caller caller) {
        return store.findByKey(idempotencyKey).filter(t -> canView(t, caller)).orElseThrow(TransferQueryService::notFound);
    }

    public Page<Transfer> search(TransferSearchCriteria criteria, Caller caller, Pageable pageable) {
        return store.search(criteria, caller, pageable);
    }

    public List<TransferHistory> history(UUID id, Caller caller) {
        get(id, caller);
        return store.history(id);
    }

    /** Operator-only (enforced at the web layer): the persisted workflow steps. */
    public List<TransferOperation> operations(UUID id, Caller caller) {
        get(id, caller);
        return operations.list(id);
    }

    private static boolean canView(Transfer t, Caller caller) {
        return caller.operator()
                || (caller.customerId() != null && caller.customerId().equals(t.getInitiatorCustomerId()));
    }

    private static ApiException notFound() {
        return ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer not found.");
    }
}
