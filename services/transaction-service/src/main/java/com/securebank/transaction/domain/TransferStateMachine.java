package com.securebank.transaction.domain;

import static com.securebank.transaction.domain.TransferStatus.COMPENSATED;
import static com.securebank.transaction.domain.TransferStatus.COMPENSATION_REQUIRED;
import static com.securebank.transaction.domain.TransferStatus.COMPLETED;
import static com.securebank.transaction.domain.TransferStatus.FAILED;
import static com.securebank.transaction.domain.TransferStatus.INITIATED;
import static com.securebank.transaction.domain.TransferStatus.PROCESSING;
import static com.securebank.transaction.domain.TransferStatus.RECONCILIATION_REQUIRED;
import static com.securebank.transaction.domain.TransferStatus.VALIDATING;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for allowed transfer state transitions.
 * <pre>
 * INITIATED               -> VALIDATING | FAILED
 * VALIDATING              -> PROCESSING | FAILED
 * PROCESSING              -> COMPLETED | FAILED | COMPENSATION_REQUIRED | RECONCILIATION_REQUIRED
 * COMPENSATION_REQUIRED   -> COMPENSATED | RECONCILIATION_REQUIRED
 * RECONCILIATION_REQUIRED -> PROCESSING | COMPENSATION_REQUIRED | FAILED
 * COMPLETED, FAILED, COMPENSATED are terminal (no outgoing transitions)
 * </pre>
 */
public final class TransferStateMachine {

    private static final Map<TransferStatus, Set<TransferStatus>> ALLOWED = new EnumMap<>(TransferStatus.class);

    static {
        ALLOWED.put(INITIATED, EnumSet.of(VALIDATING, FAILED));
        ALLOWED.put(VALIDATING, EnumSet.of(PROCESSING, FAILED));
        ALLOWED.put(PROCESSING, EnumSet.of(COMPLETED, FAILED, COMPENSATION_REQUIRED, RECONCILIATION_REQUIRED));
        ALLOWED.put(COMPENSATION_REQUIRED, EnumSet.of(COMPENSATED, RECONCILIATION_REQUIRED));
        ALLOWED.put(RECONCILIATION_REQUIRED, EnumSet.of(PROCESSING, COMPENSATION_REQUIRED, FAILED));
        ALLOWED.put(COMPLETED, EnumSet.noneOf(TransferStatus.class));
        ALLOWED.put(FAILED, EnumSet.noneOf(TransferStatus.class));
        ALLOWED.put(COMPENSATED, EnumSet.noneOf(TransferStatus.class));
    }

    private TransferStateMachine() {
    }

    public static boolean isAllowed(TransferStatus from, TransferStatus to) {
        return from != null && to != null && ALLOWED.get(from).contains(to);
    }

    public static Set<TransferStatus> allowedFrom(TransferStatus from) {
        return Collections.unmodifiableSet(ALLOWED.get(from));
    }

    public static void requireAllowed(TransferStatus from, TransferStatus to) {
        if (!isAllowed(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
    }
}
