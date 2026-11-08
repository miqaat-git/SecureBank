package com.securebank.transaction.domain;

import java.util.Set;

/**
 * Explicit policy hook for compensating steps that the remote service definitively rejected.
 *
 * <p>A rejected compensation normally cannot be repaired automatically, so the transfer stays in
 * RECONCILIATION_REQUIRED. One class of rejection is different: the account is not in a state that accepts the
 * operation <em>right now</em> (for example FROZEN), and an administrator can change that. The Account Service
 * records nothing for a rejected operation, so re-sending the SAME operation id after the cause is fixed cannot apply
 * it twice. Only these codes, only for the source-debit reversal, are re-openable by an explicit operator reconcile;
 * everything else stays terminal. Adding a code here is a business decision, not a technical one.
 */
public final class CompensationPolicy {

    private static final Set<String> OPERATOR_RECOVERABLE_CODES = Set.of("ACCOUNT_NOT_OPERATIONAL");

    private CompensationPolicy() {
    }

    /** True for a REJECTED source-debit reversal whose rejection an operator can resolve and then retry. */
    public static boolean isOperatorRecoverable(TransferOperation op) {
        return op != null
                && op.getStatus() == OperationStatus.REJECTED
                && op.getOperationType() == OperationType.REVERSE_DEBIT
                && op.getResultCode() != null
                && OPERATOR_RECOVERABLE_CODES.contains(op.getResultCode());
    }
}
