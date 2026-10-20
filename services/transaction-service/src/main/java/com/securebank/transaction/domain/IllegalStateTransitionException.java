package com.securebank.transaction.domain;

public class IllegalStateTransitionException extends RuntimeException {

    private final TransferStatus from;
    private final TransferStatus to;

    public IllegalStateTransitionException(TransferStatus from, TransferStatus to) {
        super("Illegal transfer state transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public TransferStatus getFrom() {
        return from;
    }

    public TransferStatus getTo() {
        return to;
    }
}
