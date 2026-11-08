package com.securebank.ledger.service;

import com.securebank.ledger.dto.CreateJournalEntryRequest;
import com.securebank.ledger.dto.JournalLineRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Canonical SHA-256 fingerprint of a request's business content. Two requests are "equivalent" for
 * idempotency purposes iff their fingerprints match: amounts are compared numerically (100.0 == 100.00),
 * lines are order-independent, surrounding whitespace in text is ignored. Who sent it is not included.
 */
public final class RequestFingerprint {

    private static final char RECORD = '\u001E';
    private static final char FIELD = '\u001F';

    private RequestFingerprint() {
    }

    public static String forEntry(CreateJournalEntryRequest request) {
        StringBuilder canonical = new StringBuilder("ENTRY")
                .append(RECORD).append(request.currency())
                .append(RECORD).append(request.description().trim());
        request.lines().stream()
                .sorted(Comparator.comparingInt(JournalLineRequest::lineNumber))
                .forEach(line -> canonical
                        .append(RECORD).append(line.lineNumber())
                        .append(FIELD).append(line.accountReference())
                        .append(FIELD).append(Money.normalize(line.debitAmount()).toPlainString())
                        .append(FIELD).append(Money.normalize(line.creditAmount()).toPlainString())
                        .append(FIELD).append(line.description() == null ? "" : line.description().trim()));
        return sha256Hex(canonical.toString());
    }

    public static String forReversal(UUID originalEntryId, String businessReference, String reason) {
        String canonical = "REVERSAL" + RECORD + originalEntryId
                + RECORD + businessReference + RECORD + reason.trim();
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
