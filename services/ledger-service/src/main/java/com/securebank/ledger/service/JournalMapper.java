package com.securebank.ledger.service;

import com.securebank.ledger.dto.AccountLedgerLineResponse;
import com.securebank.ledger.dto.JournalEntryResponse;
import com.securebank.ledger.dto.JournalEntrySummaryResponse;
import com.securebank.ledger.dto.JournalLineResponse;
import com.securebank.ledger.entity.JournalEntry;
import com.securebank.ledger.entity.JournalLine;
import java.util.List;
import java.util.UUID;

/** Explicit entity -> DTO mapping. Entities never leave the service layer. Call inside a transaction. */
public final class JournalMapper {

    private JournalMapper() {
    }

    public static JournalEntryResponse toResponse(JournalEntry entry, UUID reversedByEntryId) {
        List<JournalLineResponse> lines = entry.getLines().stream().map(JournalMapper::toLineResponse).toList();
        return new JournalEntryResponse(
                entry.getId(),
                entry.getBusinessReference(),
                entry.getCurrency(),
                entry.getDescription(),
                entry.getStatus(),
                entry.getEntryType(),
                entry.getTotalAmount(),
                entry.getReversesEntryId(),
                reversedByEntryId,
                entry.getCreatedBy(),
                entry.getCreatedAt(),
                lines);
    }

    public static JournalEntrySummaryResponse toSummary(JournalEntry entry) {
        return new JournalEntrySummaryResponse(
                entry.getId(),
                entry.getBusinessReference(),
                entry.getCurrency(),
                entry.getDescription(),
                entry.getStatus(),
                entry.getEntryType(),
                entry.getTotalAmount(),
                entry.getReversesEntryId(),
                entry.getCreatedAt());
    }

    public static JournalLineResponse toLineResponse(JournalLine line) {
        return new JournalLineResponse(
                line.getId(),
                line.getLineNumber(),
                line.getAccountReference(),
                line.getDebitAmount(),
                line.getCreditAmount(),
                line.getCurrency(),
                line.getDescription());
    }

    public static AccountLedgerLineResponse toAccountLine(JournalLine line) {
        return new AccountLedgerLineResponse(
                line.getId(),
                line.getEntry().getId(),
                line.getEntry().getBusinessReference(),
                line.getLineNumber(),
                line.getAccountReference(),
                line.getDebitAmount(),
                line.getCreditAmount(),
                line.getCurrency(),
                line.getDescription(),
                line.getPostedAt());
    }
}
