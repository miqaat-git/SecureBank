package com.securebank.transaction.service;

import com.securebank.transaction.domain.RequestFingerprint;
import com.securebank.transaction.client.AccountServiceClient;
import com.securebank.transaction.client.AccountView;
import com.securebank.transaction.client.RemoteCallException;
import com.securebank.transaction.config.TransferProperties;
import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferType;
import com.securebank.transaction.exception.ApiException;
import com.securebank.transaction.security.Caller;
import com.securebank.transaction.workflow.ReconciliationService;
import com.securebank.transaction.workflow.TransferProcessor;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
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
    private final TransferProperties props;
    private final AccountServiceClient accounts;
    private final AuditService audit;
    private final Clock clock;

    public TransferService(TransferRules rules, TransferCreator creator, TransferStore store,
                           TransferProcessor processor, ReconciliationService reconciliation,
                           TransferProperties props, AccountServiceClient accounts, AuditService audit,
                           Clock clock) {
        this.rules = rules;
        this.creator = creator;
        this.store = store;
        this.processor = processor;
        this.reconciliation = reconciliation;
        this.props = props;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
    }

    public InitiationResult initiate(String idempotencyKey, TransferCommand cmd, Caller caller) {
        return initiate(idempotencyKey, cmd, caller, TransferType.TRANSFER);
    }

    /**
     * Administrator-only simulated funding (D7). The service re-checks the ADMIN role independently of the web layer
     * and never accepts a source. Denials, validation failures and idempotency conflicts are audited in their own
     * transaction; a replay of the same key and request returns the original event without a new workflow.
     */
    public InitiationResult initiateFunding(String idempotencyKey, FundingCommand cmd, Caller caller) {
        if (caller == null || !caller.admin()) {
            auditDenied("FUNDING_DENIED", caller, cmd, "ADMIN_REQUIRED");
            throw ApiException.forbidden("ADMIN_REQUIRED", "Only an administrator can fund accounts.");
        }
        if (!props.funding().enabled()) {
            throw ApiException.unavailable("FUNDING_NOT_AVAILABLE", "Account funding is not available.");
        }
        String scope = adminScope(caller);
        BigDecimal amount;
        try {
            amount = rules.validateFunding(idempotencyKey, cmd);
        } catch (ApiException e) {
            // A key reuse with a changed payload must surface as 409 even when the new payload is also invalid.
            InitiationResult existing = existingFundingForKey(idempotencyKey, cmd, scope, caller);
            if (existing != null) {
                return existing;
            }
            auditDenied("FUNDING_REJECTED", caller, cmd, e.getCode());
            throw e;
        }
        String currency = cmd.currency().toUpperCase(Locale.ROOT);
        String reason = cmd.reason().trim();
        String fingerprint = RequestFingerprint.ofFunding(scope, cmd.targetAccountId(), amount, currency,
                cmd.fundingReference(), reason);
        InitiationResult existing = existingFundingForKey(idempotencyKey, cmd, scope, caller);
        if (existing != null) {
            return existing;
        }
        if (store.fundingReferenceExists(cmd.fundingReference())) {
            auditDenied("FUNDING_CONFLICT", caller, cmd, "FUNDING_REFERENCE_REUSED");
            throw ApiException.conflict("FUNDING_REFERENCE_REUSED", "This funding reference was already used.");
        }
        Transfer candidate = Transfer.initiateFunding(UUID.randomUUID(), idempotencyKey, fingerprint,
                cmd.targetAccountId(), amount, currency, caller.subject(), scope, cmd.fundingReference(), reason,
                caller.customerId(), clock.instant());
        TransferCreator.Result result;
        try {
            result = creator.createOrGet(candidate);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            auditDenied("FUNDING_CONFLICT", caller, cmd, "FUNDING_REFERENCE_REUSED");
            throw ApiException.conflict("FUNDING_REFERENCE_REUSED", "This funding reference was already used.");
        }
        if (!result.created()) {
            if (!result.transfer().getRequestFingerprint().equals(fingerprint)
                    || result.transfer().getTransferType() != TransferType.FUNDING) {
                auditDenied("FUNDING_CONFLICT", caller, cmd, "IDEMPOTENCY_KEY_REUSED");
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request.");
            }
            return new InitiationResult(result.transfer(), true);
        }
        processor.process(result.transfer().getId());
        return new InitiationResult(store.require(result.transfer().getId()), false);
    }

    static String adminScope(Caller caller) {
        return "ADMIN:" + caller.subject();
    }

    private InitiationResult existingFundingForKey(String idempotencyKey, FundingCommand cmd, String scope,
                                                   Caller caller) {
        if (idempotencyKey == null || cmd == null || cmd.targetAccountId() == null || cmd.amount() == null
                || cmd.currency() == null || cmd.fundingReference() == null) {
            return null;
        }
        Transfer found = store.findByKey(scope, idempotencyKey).orElse(null);
        if (found == null) {
            return null;
        }
        BigDecimal canonical = cmd.amount().stripTrailingZeros().scale() <= 2 ? cmd.amount().setScale(2) : null;
        String fingerprint = canonical == null ? "" : RequestFingerprint.ofFunding(scope, cmd.targetAccountId(),
                canonical, cmd.currency(), cmd.fundingReference(), cmd.reason() == null ? "" : cmd.reason().trim());
        if (found.getTransferType() != TransferType.FUNDING || !found.getRequestFingerprint().equals(fingerprint)) {
            auditDenied("FUNDING_CONFLICT", caller, cmd, "IDEMPOTENCY_KEY_REUSED");
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request.");
        }
        return new InitiationResult(found, true);
    }

    private void auditDenied(String type, Caller caller, FundingCommand cmd, String code) {
        if (caller == null) {
            return;
        }
        audit.recordIndependent(audit.event(type, "FAILURE", caller, null,
                cmd == null ? null : cmd.targetAccountId(), cmd == null ? null : cmd.amount(),
                cmd == null || cmd.currency() == null ? null : cmd.currency().toUpperCase(Locale.ROOT),
                cmd == null ? null : cmd.fundingReference(), cmd == null ? null : cmd.reason(), code));
    }

    private InitiationResult initiate(String idempotencyKey, TransferCommand cmd, Caller caller, TransferType type) {
        InitiationResult existing = existingForKey(idempotencyKey, cmd, caller, type);
        if (existing != null) {
            return existing;
        }
        BigDecimal amount = rules.validate(idempotencyKey, cmd);
        if (caller.customerId() == null) {
            throw ApiException.forbidden("CUSTOMER_IDENTITY_UNAVAILABLE",
                    "The authenticated identity is not linked to a customer.");
        }
        if (type == TransferType.TRANSFER) {
            requireOwnedSource(cmd, caller);
        }
        String currency = cmd.currency().toUpperCase(Locale.ROOT);
        String fingerprint = RequestFingerprint.of(caller.customerId(), cmd.sourceAccountId(),
                cmd.destinationAccountId(), amount, currency, type);

        Transfer candidate = Transfer.initiate(UUID.randomUUID(), idempotencyKey, fingerprint,
                cmd.sourceAccountId(), cmd.destinationAccountId(), amount, currency, caller.subject(),
                caller.customerId(), type, clock.instant());

        TransferCreator.Result result = creator.createOrGet(candidate);
        if (!result.created()) {
            // Race path (a concurrent request created the row first) and replay path share the same rule: the
            // fingerprint (which includes the operation type) AND the stored type must both match.
            if (!result.transfer().getRequestFingerprint().equals(fingerprint)
                    || result.transfer().getTransferType() != type) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request.");
            }
            return new InitiationResult(result.transfer(), true);
        }
        processor.process(result.transfer().getId());
        return new InitiationResult(store.require(result.transfer().getId()), false);
    }

    /**
     * Ownership privacy: a source account that does not exist and one that belongs to another customer produce the
     * same 404, so a customer cannot probe which account ids exist. If Account Service cannot answer, the saga's own
     * validation (which retries and re-checks ownership) decides, so an outage never turns into a false 404.
     */
    private void requireOwnedSource(TransferCommand cmd, Caller caller) {
        Optional<AccountView> source;
        try {
            source = accounts.findAccount(cmd.sourceAccountId());
        } catch (RemoteCallException unavailable) {
            return;
        }
        if (source == null || source.isEmpty() || source.get().customerId() == null
                || !source.get().customerId().equals(caller.customerId())) {
            throw ApiException.notFound("SOURCE_ACCOUNT_NOT_FOUND", "The source account was not found.");
        }
    }

    /**
     * If this customer already has a transfer under the key, decide replay vs conflict BEFORE business validation,
     * so e.g. an unsupported currency cannot mask IDEMPOTENCY_KEY_REUSED. Does nothing (returns null) when the input
     * is incomplete or no transfer exists; normal validation and the race-safe create then apply unchanged.
     */
    private InitiationResult existingForKey(String idempotencyKey, TransferCommand cmd, Caller caller,
                                            TransferType type) {
        if (idempotencyKey == null || caller.customerId() == null || cmd == null
                || cmd.sourceAccountId() == null || cmd.destinationAccountId() == null
                || cmd.amount() == null || cmd.currency() == null) {
            return null;
        }
        Transfer found = store.findByKey(caller.customerId(), idempotencyKey).orElse(null);
        if (found == null) {
            return null;
        }
        String fingerprint = RequestFingerprint.ofUnvalidated(caller.customerId(), cmd.sourceAccountId(),
                cmd.destinationAccountId(), cmd.amount(), cmd.currency(), type);
        if (!found.getRequestFingerprint().equals(fingerprint) || found.getTransferType() != type) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request.");
        }
        return new InitiationResult(found, true);
    }

    /** Administrator action: resume an incomplete (non-reconciliation) transfer now. */
    public Transfer retry(UUID transferId, Caller caller) {
        requireAdmin(caller, "TRANSFER_RETRY");
        Transfer t = store.require(transferId);
        if (!t.getStatus().isRecoverable()) {
            throw ApiException.conflict("INVALID_STATE",
                    "Only INITIATED, VALIDATING, PROCESSING or COMPENSATION_REQUIRED transfers can be retried; "
                            + "use reconcile for RECONCILIATION_REQUIRED.");
        }
        audit.recordIndependent(audit.event("TRANSFER_RETRY", "ACCEPTED", caller, transferId,
                t.getDestinationAccountId(), t.getAmount(), t.getCurrency(), t.getFundingReference(), null, null));
        processor.process(transferId);
        return store.require(transferId);
    }

    /** Administrator action: re-query remote outcomes of an ambiguous transfer and resume or compensate. */
    public Transfer reconcile(UUID transferId, Caller caller) {
        requireAdmin(caller, "TRANSFER_RECONCILE");
        Transfer t = store.require(transferId);
        audit.recordIndependent(audit.event("TRANSFER_RECONCILE", "ACCEPTED", caller, transferId,
                t.getDestinationAccountId(), t.getAmount(), t.getCurrency(), t.getFundingReference(), null, null));
        return reconciliation.reconcile(transferId);
    }

    private void requireAdmin(Caller caller, String eventType) {
        if (caller == null || !caller.admin()) {
            if (caller != null) {
                audit.recordIndependent(audit.event(eventType + "_DENIED", "FAILURE", caller, null, null, null, null,
                        null, null, "ADMIN_REQUIRED"));
            }
            throw ApiException.forbidden("ADMIN_REQUIRED", "Administrator role required.");
        }
    }
}
