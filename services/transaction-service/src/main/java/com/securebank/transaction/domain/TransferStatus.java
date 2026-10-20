package com.securebank.transaction.domain;

import java.util.EnumSet;
import java.util.Set;

public enum TransferStatus {
    INITIATED,
    VALIDATING,
    PROCESSING,
    COMPLETED,
    FAILED,
    COMPENSATION_REQUIRED,
    COMPENSATED,
    RECONCILIATION_REQUIRED;

    /** Terminal states are never changed again. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == COMPENSATED;
    }

    /** States the automatic engine can advance without operator input. */
    public boolean isRecoverable() {
        return this == INITIATED || this == VALIDATING || this == PROCESSING || this == COMPENSATION_REQUIRED;
    }

    public static Set<TransferStatus> recoverableStatuses() {
        return EnumSet.of(INITIATED, VALIDATING, PROCESSING, COMPENSATION_REQUIRED);
    }
}
