package com.securebank.transaction.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact JSON this service sends to Account and Ledger. The receiving services pin the same field names in
 * their own tests (Account: InternalOperationShapeTest, Ledger: InternalWireShapeTest). If either side changes a
 * name, one of the three tests fails, so contract drift cannot go unnoticed even though the modules are built
 * separately. This is a serialisation/shape test, not a live cross-service call.
 */
class WireContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void accountOperationRequestHasExactlyTheFieldsAccountServiceAccepts() {
        JsonNode json = MAPPER.valueToTree(new AccountOperationRequest(UUID.randomUUID(), UUID.randomUUID(),
                AccountOperationType.REVERSE_DEBIT, new BigDecimal("10.00"), "INR", UUID.randomUUID(),
                UUID.randomUUID()));

        assertThat(fields(json)).containsExactlyInAnyOrder("operationId", "type", "amount", "currency",
                "transferId", "reversesOperationId");          // accountId travels in the path only
        assertThat(json.get("type").asText()).isEqualTo("REVERSE_DEBIT");
        assertThat(json.get("amount").isNumber()).isTrue();
    }

    @Test
    void ledgerPostRequestHasExactlyTheFieldsTheLedgerInternalAdapterAccepts() {
        JsonNode json = MAPPER.valueToTree(new LedgerPostRequest("TRANSFER-" + UUID.randomUUID(), UUID.randomUUID(),
                "Transfer", "INR", List.of(
                new LedgerEntryLine(UUID.randomUUID(), "DEBIT", new BigDecimal("10.00")),
                new LedgerEntryLine(UUID.randomUUID(), "CREDIT", new BigDecimal("10.00")))));

        assertThat(fields(json)).containsExactlyInAnyOrder("businessReference", "idempotencyKey", "description",
                "currency", "entries");
        assertThat(fields(json.get("entries").get(0))).containsExactlyInAnyOrder("accountId", "direction", "amount");
        assertThat(json.get("entries").get(0).get("direction").asText()).isEqualTo("DEBIT");
        assertThat(json.get("entries").get(1).get("direction").asText()).isEqualTo("CREDIT");
    }

    @Test
    void ledgerReversalRequestCarriesTheOriginalReferenceInThePathNotTheBody() {
        JsonNode json = MAPPER.valueToTree(new LedgerReversalRequest("TRANSFER-A", "TRANSFER-A-REVERSAL",
                UUID.randomUUID(), "TRANSFER_COMPENSATION"));

        assertThat(fields(json)).containsExactlyInAnyOrder("reversalBusinessReference", "idempotencyKey", "reason");
    }
}
