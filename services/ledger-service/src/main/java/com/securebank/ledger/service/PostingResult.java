package com.securebank.ledger.service;

import com.securebank.ledger.dto.JournalEntryResponse;

/** Outcome of a write. {@code created=false} means an equivalent earlier request was replayed (idempotent retry). */
public record PostingResult(JournalEntryResponse entry, boolean created) {
}
