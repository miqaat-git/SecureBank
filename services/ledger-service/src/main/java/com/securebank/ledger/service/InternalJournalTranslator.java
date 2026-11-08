package com.securebank.ledger.service;

import com.securebank.ledger.dto.CreateJournalEntryRequest;
import com.securebank.ledger.dto.InternalEntryLine;
import com.securebank.ledger.dto.InternalPostRequest;
import com.securebank.ledger.dto.InternalReversalRequest;
import com.securebank.ledger.dto.JournalLineRequest;
import com.securebank.ledger.dto.ReverseEntryRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Pure translation from the Transaction Service wire format to the Ledger's canonical requests. It contains no
 * accounting rules: balance, precision, line count and sign checks stay in {@link JournalEntryValidator}.
 *
 * <p>Normalisation is the only place references are canonicalised (upper case, {@link Locale#ROOT}), so posting,
 * lookup and reversal always agree. Account identifiers are UUIDs, so their canonical form is the upper-case
 * hyphenated string.
 */
public final class InternalJournalTranslator {

    private InternalJournalTranslator() {
    }

    public static String normalizeReference(String reference) {
        return reference == null ? null : reference.toUpperCase(Locale.ROOT);
    }

    public static String accountReference(UUID accountId) {
        return accountId.toString().toUpperCase(Locale.ROOT);
    }

    /** Lines are numbered 1..n in request order, so an identical request always yields an identical fingerprint. */
    public static CreateJournalEntryRequest toCreateRequest(InternalPostRequest request) {
        List<JournalLineRequest> lines = new ArrayList<>();
        int lineNumber = 1;
        for (InternalEntryLine entry : request.entries()) {
            boolean debit = "DEBIT".equals(entry.direction());
            lines.add(new JournalLineRequest(lineNumber++, accountReference(entry.accountId()),
                    debit ? entry.amount() : BigDecimal.ZERO,
                    debit ? BigDecimal.ZERO : entry.amount(),
                    null, null));
        }
        return new CreateJournalEntryRequest(normalizeReference(request.businessReference()),
                request.currency().toUpperCase(Locale.ROOT), request.description(), lines);
    }

    public static ReverseEntryRequest toReverseRequest(InternalReversalRequest request) {
        return new ReverseEntryRequest(normalizeReference(request.reversalBusinessReference()), request.reason());
    }
}
