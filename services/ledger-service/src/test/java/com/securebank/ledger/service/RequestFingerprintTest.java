package com.securebank.ledger.service;

import static com.securebank.ledger.support.TestData.entry;
import static com.securebank.ledger.support.TestData.line;
import static com.securebank.ledger.support.TestData.withLines;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void identicalRequestsHaveIdenticalFingerprints() {
        assertThat(RequestFingerprint.forEntry(entry("TXN-1", "ACC-A", "ACC-B", "100.00")))
                .isEqualTo(RequestFingerprint.forEntry(entry("TXN-1", "ACC-A", "ACC-B", "100.00")));
    }

    @Test
    void amountScaleDoesNotChangeFingerprint() {
        assertThat(RequestFingerprint.forEntry(entry("TXN-1", "ACC-A", "ACC-B", "100.0")))
                .isEqualTo(RequestFingerprint.forEntry(entry("TXN-1", "ACC-A", "ACC-B", "100.00")));
    }

    @Test
    void lineOrderDoesNotChangeFingerprint() {
        var first = withLines("TXN-1", line(1, "ACC-A", "5", "0", null), line(2, "ACC-B", "0", "5", null));
        var swapped = withLines("TXN-1", line(2, "ACC-B", "0", "5", null), line(1, "ACC-A", "5", "0", null));
        assertThat(RequestFingerprint.forEntry(first)).isEqualTo(RequestFingerprint.forEntry(swapped));
    }

    @Test
    void differentAmountAccountOrDescriptionChangesFingerprint() {
        String base = RequestFingerprint.forEntry(entry("TXN-1", "ACC-A", "ACC-B", "100.00"));
        assertThat(RequestFingerprint.forEntry(entry("TXN-1", "ACC-A", "ACC-B", "100.01"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.forEntry(entry("TXN-1", "ACC-A", "ACC-C", "100.00"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.forEntry(entry("TXN-2", "ACC-A", "ACC-B", "100.00"))).isNotEqualTo(base);
    }

    @Test
    void reversalFingerprintDependsOnEntryReferenceAndReason() {
        UUID id = UUID.randomUUID();
        String base = RequestFingerprint.forReversal(id, "REV-1", "duplicate");
        assertThat(RequestFingerprint.forReversal(id, "REV-1", "  duplicate ")).isEqualTo(base);
        assertThat(RequestFingerprint.forReversal(id, "REV-2", "duplicate")).isNotEqualTo(base);
        assertThat(RequestFingerprint.forReversal(id, "REV-1", "other")).isNotEqualTo(base);
        assertThat(RequestFingerprint.forReversal(UUID.randomUUID(), "REV-1", "duplicate")).isNotEqualTo(base);
    }
}
