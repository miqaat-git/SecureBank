package com.securebank.transaction.domain;

/** The durable workflow steps. Each type exists at most once per transfer (unique constraint). */
public enum OperationType {
    DEBIT_SOURCE(TargetService.ACCOUNT_SERVICE, false),
    LEDGER_POST(TargetService.LEDGER_SERVICE, false),
    CREDIT_DESTINATION(TargetService.ACCOUNT_SERVICE, false),
    LEDGER_REVERSAL(TargetService.LEDGER_SERVICE, true),
    REVERSE_DEBIT(TargetService.ACCOUNT_SERVICE, true);

    private final TargetService targetService;
    private final boolean compensation;

    OperationType(TargetService targetService, boolean compensation) {
        this.targetService = targetService;
        this.compensation = compensation;
    }

    public TargetService targetService() {
        return targetService;
    }

    public boolean isCompensation() {
        return compensation;
    }
}
