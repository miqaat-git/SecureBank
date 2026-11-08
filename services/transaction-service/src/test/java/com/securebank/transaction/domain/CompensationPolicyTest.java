package com.securebank.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompensationPolicyTest {

    private static TransferOperation rejected(OperationType type, String code) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        TransferOperation op = TransferOperation.create(UUID.randomUUID(), type, now);
        op.markFinal(OperationStatus.REJECTED, code, now);
        return op;
    }

    @Test
    void onlyANonOperationalAccountRejectionOfTheDebitReversalIsOperatorRecoverable() {
        assertThat(CompensationPolicy.isOperatorRecoverable(
                rejected(OperationType.REVERSE_DEBIT, "ACCOUNT_NOT_OPERATIONAL"))).isTrue();
    }

    @Test
    void otherCodesAndOtherStepsStayTerminal() {
        assertThat(CompensationPolicy.isOperatorRecoverable(
                rejected(OperationType.REVERSE_DEBIT, "ACCOUNT_CLOSED"))).isFalse();
        assertThat(CompensationPolicy.isOperatorRecoverable(
                rejected(OperationType.REVERSE_DEBIT, "INVALID_REVERSAL"))).isFalse();
        assertThat(CompensationPolicy.isOperatorRecoverable(
                rejected(OperationType.DEBIT_SOURCE, "ACCOUNT_NOT_OPERATIONAL"))).isFalse();
        assertThat(CompensationPolicy.isOperatorRecoverable(
                rejected(OperationType.LEDGER_REVERSAL, "ACCOUNT_NOT_OPERATIONAL"))).isFalse();
        assertThat(CompensationPolicy.isOperatorRecoverable(null)).isFalse();
    }

    @Test
    void aStepThatIsNotRejectedIsNeverRecoverable() {
        TransferOperation pending = TransferOperation.create(UUID.randomUUID(), OperationType.REVERSE_DEBIT,
                Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(CompensationPolicy.isOperatorRecoverable(pending)).isFalse();
    }

    @Test
    void reopeningKeepsTheOperationIdAndRecordsThePreviousRejection() {
        TransferOperation op = rejected(OperationType.REVERSE_DEBIT, "ACCOUNT_NOT_OPERATIONAL");
        UUID id = op.getOperationId();
        op.reopenForRetry(Instant.parse("2026-01-02T00:00:00Z"));

        assertThat(op.getOperationId()).isEqualTo(id);
        assertThat(op.getStatus()).isEqualTo(OperationStatus.PENDING);
        assertThat(op.getAttemptCount()).isZero();
        assertThat(op.getLastError()).isEqualTo("ACCOUNT_NOT_OPERATIONAL");
    }

    @Test
    void reopeningDoesNothingToASucceededStep() {
        TransferOperation op = TransferOperation.create(UUID.randomUUID(), OperationType.REVERSE_DEBIT,
                Instant.parse("2026-01-01T00:00:00Z"));
        op.markFinal(OperationStatus.SUCCEEDED, "OK", Instant.parse("2026-01-01T00:00:01Z"));
        op.reopenForRetry(Instant.parse("2026-01-02T00:00:00Z"));
        assertThat(op.getStatus()).isEqualTo(OperationStatus.SUCCEEDED);
    }
}
