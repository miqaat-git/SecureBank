package com.securebank.ledger.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the field names the internal adapter accepts. Transaction Service pins the same names for what it sends
 * (WireContractTest); the Ledger rejects unknown JSON properties, so a mismatch would fail every posting.
 */
class InternalWireShapeTest {

    private static List<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @Test
    void postRequestMatchesWhatTransactionServiceSends() {
        assertThat(components(InternalPostRequest.class)).containsExactlyInAnyOrder(
                "businessReference", "idempotencyKey", "description", "currency", "entries");
        assertThat(components(InternalEntryLine.class)).containsExactlyInAnyOrder("accountId", "direction", "amount");
    }

    @Test
    void reversalRequestMatchesWhatTransactionServiceSends() {
        assertThat(components(InternalReversalRequest.class)).containsExactlyInAnyOrder(
                "reversalBusinessReference", "idempotencyKey", "reason");
    }
}
