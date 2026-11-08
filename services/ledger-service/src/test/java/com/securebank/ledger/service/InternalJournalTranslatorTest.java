package com.securebank.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.securebank.ledger.dto.CreateJournalEntryRequest;
import com.securebank.ledger.dto.InternalEntryLine;
import com.securebank.ledger.dto.InternalPostRequest;
import com.securebank.ledger.dto.InternalReversalRequest;
import com.securebank.ledger.dto.JournalLineRequest;
import com.securebank.ledger.dto.ReverseEntryRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure mapping tests: Transaction wire format -> Ledger canonical requests. No Spring context. */
class InternalJournalTranslatorTest {

    private static final UUID SOURCE = UUID.fromString("0a1b2c3d-1111-2222-3333-444455556666");
    private static final UUID DEST = UUID.fromString("ffeeddcc-aaaa-bbbb-cccc-ddddeeeeffff");

    private static InternalPostRequest request(String reference, List<InternalEntryLine> lines) {
        return new InternalPostRequest(reference, UUID.randomUUID(), "Transfer", "inr", lines);
    }

    @Test
    void mapsDebitAndCreditToTheMatchingAmountFieldsWithDeterministicLineNumbers() {
        CreateJournalEntryRequest result = InternalJournalTranslator.toCreateRequest(request(
                "transfer-" + UUID.randomUUID(), List.of(
                        new InternalEntryLine(SOURCE, "DEBIT", new BigDecimal("125.50")),
                        new InternalEntryLine(DEST, "CREDIT", new BigDecimal("125.50")))));

        assertThat(result.lines()).hasSize(2);
        JournalLineRequest debit = result.lines().get(0);
        JournalLineRequest credit = result.lines().get(1);
        assertThat(debit.lineNumber()).isEqualTo(1);
        assertThat(debit.debitAmount()).isEqualByComparingTo("125.50");
        assertThat(debit.creditAmount()).isEqualByComparingTo("0");
        assertThat(credit.lineNumber()).isEqualTo(2);
        assertThat(credit.debitAmount()).isEqualByComparingTo("0");
        assertThat(credit.creditAmount()).isEqualByComparingTo("125.50");
        assertThat(result.currency()).isEqualTo("INR");
        assertThat(result.description()).isEqualTo("Transfer");
    }

    @Test
    void lowerCaseUuidsAndReferencesAreCanonicalisedToUpperCase() {
        String reference = "transfer-" + SOURCE;
        CreateJournalEntryRequest result = InternalJournalTranslator.toCreateRequest(request(reference, List.of(
                new InternalEntryLine(SOURCE, "DEBIT", BigDecimal.TEN),
                new InternalEntryLine(DEST, "CREDIT", BigDecimal.TEN))));

        assertThat(result.businessReference()).isEqualTo(("TRANSFER-" + SOURCE).toUpperCase());
        assertThat(result.lines().get(0).accountReference()).isEqualTo("0A1B2C3D-1111-2222-3333-444455556666");
        assertThat(result.lines().get(1).accountReference()).isEqualTo("FFEEDDCC-AAAA-BBBB-CCCC-DDDDEEEEFFFF");
        assertThat(InternalJournalTranslator.normalizeReference("abc-def")).isEqualTo("ABC-DEF");
        assertThat(InternalJournalTranslator.normalizeReference(null)).isNull();
    }

    @Test
    void numbersManyLinesSequentiallyInRequestOrder() {
        CreateJournalEntryRequest result = InternalJournalTranslator.toCreateRequest(request("txn-multi-1", List.of(
                new InternalEntryLine(SOURCE, "DEBIT", new BigDecimal("30")),
                new InternalEntryLine(DEST, "CREDIT", new BigDecimal("10")),
                new InternalEntryLine(UUID.randomUUID(), "CREDIT", new BigDecimal("20")),
                new InternalEntryLine(UUID.randomUUID(), "DEBIT", new BigDecimal("0.01")))));

        assertThat(result.lines()).extracting(JournalLineRequest::lineNumber).containsExactly(1, 2, 3, 4);
    }

    @Test
    void identicalRequestsProduceIdenticalFingerprints() {
        InternalPostRequest first = request("transfer-same-1", List.of(
                new InternalEntryLine(SOURCE, "DEBIT", BigDecimal.TEN),
                new InternalEntryLine(DEST, "CREDIT", BigDecimal.TEN)));
        InternalPostRequest second = new InternalPostRequest(first.businessReference(), UUID.randomUUID(),
                first.description(), first.currency(), first.entries());

        assertThat(RequestFingerprint.forEntry(InternalJournalTranslator.toCreateRequest(first)))
                .isEqualTo(RequestFingerprint.forEntry(InternalJournalTranslator.toCreateRequest(second)));
    }

    @Test
    void reversalRequestUsesTheNormalisedReversalReference() {
        ReverseEntryRequest result = InternalJournalTranslator.toReverseRequest(
                new InternalReversalRequest("transfer-abc-reversal", UUID.randomUUID(), "TRANSFER_COMPENSATION"));

        assertThat(result.businessReference()).isEqualTo("TRANSFER-ABC-REVERSAL");
        assertThat(result.reason()).isEqualTo("TRANSFER_COMPENSATION");
    }
}
