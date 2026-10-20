package com.securebank.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferStateMachineTest {

    private static final Map<TransferStatus, Set<TransferStatus>> EXPECTED = Map.of(
            TransferStatus.INITIATED, EnumSet.of(TransferStatus.VALIDATING, TransferStatus.FAILED),
            TransferStatus.VALIDATING, EnumSet.of(TransferStatus.PROCESSING, TransferStatus.FAILED),
            TransferStatus.PROCESSING, EnumSet.of(TransferStatus.COMPLETED, TransferStatus.FAILED,
                    TransferStatus.COMPENSATION_REQUIRED, TransferStatus.RECONCILIATION_REQUIRED),
            TransferStatus.COMPENSATION_REQUIRED,
            EnumSet.of(TransferStatus.COMPENSATED, TransferStatus.RECONCILIATION_REQUIRED),
            TransferStatus.RECONCILIATION_REQUIRED,
            EnumSet.of(TransferStatus.PROCESSING, TransferStatus.COMPENSATION_REQUIRED, TransferStatus.FAILED),
            TransferStatus.COMPLETED, EnumSet.noneOf(TransferStatus.class),
            TransferStatus.FAILED, EnumSet.noneOf(TransferStatus.class),
            TransferStatus.COMPENSATED, EnumSet.noneOf(TransferStatus.class));

    @Test
    void everyPairMatchesTheDocumentedTransitionTable() {
        for (TransferStatus from : TransferStatus.values()) {
            for (TransferStatus to : TransferStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);
                assertThat(TransferStateMachine.isAllowed(from, to))
                        .as("%s -> %s", from, to).isEqualTo(expected);
            }
        }
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        for (TransferStatus s : TransferStatus.values()) {
            assertThat(s.isTerminal()).isEqualTo(TransferStateMachine.allowedFrom(s).isEmpty());
        }
        assertThat(TransferStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(TransferStatus.RECONCILIATION_REQUIRED.isTerminal()).isFalse();
    }

    @Test
    void completedCanOnlyBeReachedFromProcessing() {
        for (TransferStatus s : TransferStatus.values()) {
            assertThat(TransferStateMachine.isAllowed(s, TransferStatus.COMPLETED))
                    .isEqualTo(s == TransferStatus.PROCESSING);
        }
    }

    @Test
    void entityRejectsIllegalTransitionAndKeepsItsState() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Transfer t = Transfer.initiate(UUID.randomUUID(), "key-12345678", "fp", UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.00"), "INR", "user", "CUST-1", now);

        assertThatThrownBy(() -> t.transitionTo(TransferStatus.COMPLETED, null, now.plusSeconds(1)))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.INITIATED);
        assertThat(t.getCompletedAt()).isNull();
        assertThat(t.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void terminalEntityCannotBeReset() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Transfer t = Transfer.initiate(UUID.randomUUID(), "key-12345678", "fp", UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.00"), "INR", "user", "CUST-1", now);
        t.transitionTo(TransferStatus.FAILED, "INSUFFICIENT_FUNDS", now.plusSeconds(1));

        assertThat(t.getErrorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(t.getErrorMessage()).isEqualTo("The source account has insufficient available funds.");
        assertThat(t.getNextAttemptAt()).isNull();
        assertThatThrownBy(() -> t.transitionTo(TransferStatus.INITIATED, null, now.plusSeconds(2)))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(t.getStatus()).isEqualTo(TransferStatus.FAILED);
    }

    @Test
    void completionStampsCompletionTimeAndClearsErrors() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Transfer t = Transfer.initiate(UUID.randomUUID(), "key-12345678", "fp", UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.00"), "INR", "user", "CUST-1", now);
        t.transitionTo(TransferStatus.VALIDATING, null, now);
        t.transitionTo(TransferStatus.PROCESSING, null, now);
        t.transitionTo(TransferStatus.RECONCILIATION_REQUIRED, "OUTCOME_UNKNOWN", now.plusSeconds(1));
        assertThat(t.getNextAttemptAt()).isNull();
        t.transitionTo(TransferStatus.PROCESSING, null, now.plusSeconds(2));
        t.transitionTo(TransferStatus.COMPLETED, null, now.plusSeconds(3));

        assertThat(t.getCompletedAt()).isEqualTo(now.plusSeconds(3));
        assertThat(t.getErrorCode()).isNull();
    }
}
