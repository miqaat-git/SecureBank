package com.securebank.ledger.repository;

import com.securebank.ledger.entity.JournalEntry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface JournalEntryRepository
        extends JpaRepository<JournalEntry, UUID>, JpaSpecificationExecutor<JournalEntry> {

    Optional<JournalEntry> findByBusinessReference(String businessReference);

    /** The (at most one) reversal entry that compensates the given original entry. */
    Optional<JournalEntry> findByReversesEntryId(UUID reversesEntryId);
}
