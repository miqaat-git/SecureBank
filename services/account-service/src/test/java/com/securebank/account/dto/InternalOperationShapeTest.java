package com.securebank.account.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the field names of the canonical internal operation request. Transaction Service pins the same names for
 * what it sends (WireContractTest); Account rejects unknown JSON properties, so drift would fail every operation.
 */
class InternalOperationShapeTest {

    @Test
    void requestMatchesWhatTransactionServiceSends() {
        List<String> names = Arrays.stream(InternalOperationRequest.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(names).containsExactlyInAnyOrder("operationId", "type", "amount", "currency", "transferId",
                "reversesOperationId");
    }

    @Test
    void responseCarriesTheStatusFieldTransactionServiceRequiresForSuccess() {
        List<String> names = Arrays.stream(InternalOperationResponse.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(names).contains("status", "operationId");
        assertThat(InternalOperationResponse.SUCCEEDED).isEqualTo("SUCCEEDED");
    }
}
