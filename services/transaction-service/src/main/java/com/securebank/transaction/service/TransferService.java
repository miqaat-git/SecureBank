package com.securebank.transaction.service;

import com.securebank.transaction.domain.RequestFingerprint;
import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.exception.ApiException;
import com.securebank.transaction.security.Caller;
import com.securebank.transaction.workflow.ReconciliationService;
import com.securebank.transaction.workflow.TransferProcessor;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Application commands: initiate, retry, reconcile. Not transactional: the workflow commits step by step. */
@Service
public class TransferService {

    private final TransferRules rules;
    private final TransferCreator creator;
    private final TransferStore store;
    private final TransferProcessor processor;
    private final ReconciliationService reconciliation;
    private final Clock clock;

    public TransferService(TransferRules rules, TransferCreator creator, TransferStore store,
                           TransferProcessor processor, ReconciliationService reconciliation, Clock clock) {
        this.rules = rules;
        this.creator = creator;
        this.store = store;
        this.processor = processor;
        this.reconciliation = reconciliation;
        this.clock = clock;
    }

    public InitiationResult initiate(String idempotencyKey, TransferCommand cmd, Caller caller) {
        BigDecimal amount = rules.validate(idempotencyKey, cmd);
        if (caller.customerId() == null) {
            throw ApiException.forbidden("CUSTOMER_IDENTITY_UNAVAILABLE",
                    "The authenticated identity is not linked to a customer.");
        }
        String currency = cmd.currency().toUpperCase(Locale.ROOT);
        String fingerprint = RequestFingerprint.of(caller.customerId(), cmd.sourceAccountId(),
                cmd.destinationAccountId(), amount, currency);

        Transfer candidate = Transfer.initiate(UUID.randomUUID(), idempotencyKey, fingerprint,
                cmd.sourceAccountId(), cmd.destinationAccountId(), amount, currency, caller.subject(),
                caller.customerId(), clock.instant());

        TransferCreator.Result result = creator.createOrGet(candidate);
        if (!result.created()) {
            if (!result.transfer().getRequestFingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request.");
            }
            return new InitiationResult(result.transfer(), true);
        }
        processor.process(result.transfer().getId());
        return new InitiationResult(store.require(result.transfer().getId()), false);
    }

    /** Operator action: resume an incomplete (non-reconciliation) transfer now. */
    public Transfer retry(UUID transferId) {
        Transfer t = store.require(transferId);
        if (!t.getStatus().isRecoverable()) {
            throw ApiException.conflict("INVALID_STATE",
                    "Only INITIATED, VALIDATING, PROCESSING or COMPENSATION_REQUIRED transfers can be retried; "
                            + "use reconcile for RECONCILIATION_REQUIRED.");
        }
        processor.process(transferId);
        return store.require(transferId);
    }

    /** Operator action: re-query remote outcomes of an ambiguous transfer and resume or compensate. */
    public Transfer reconcile(UUID transferId) {
        store.require(transferId);
        return reconciliation.reconcile(transferId);
    }
}
