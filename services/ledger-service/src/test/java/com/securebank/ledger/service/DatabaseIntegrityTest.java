package com.securebank.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.ledger.entity.JournalEntry;
import com.securebank.ledger.enums.EntryType;
import com.securebank.ledger.repository.JournalEntryRepository;
import com.securebank.ledger.support.AbstractLedgerTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies that the database itself (Flyway DDL) protects ledger integrity, independent of Java checks,
 * and that a failure part-way through persisting an entry rolls back everything.
 */
class DatabaseIntegrityTest extends AbstractLedgerTest {

    @Autowired
    private JournalEntryRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static JournalEntry newEntry(String reference) {
        return new JournalEntry(reference, "INR", "integrity test", EntryType.NORMAL, null,
                new BigDecimal("10.0000"), "f".repeat(64), "tester", Instant.now());
    }

    private void insertRawEntry(UUID id, String reference) {
        jdbc.update("insert into journal_entries (id, business_reference, currency, description, status, entry_type,"
                        + " total_amount, reverses_entry_id, request_fingerprint, created_by, created_at)"
                        + " values (?, ?, 'INR', 'raw', 'POSTED', 'NORMAL', 10.0000, null, ?, 'tester', ?)",
                id.toString(), reference, "a".repeat(64), Timestamp.from(Instant.now()));
    }

    private void insertRawLine(UUID entryId, int lineNumber, String debit, String credit) {
        jdbc.update("insert into journal_lines (id, entry_id, line_number, account_reference, debit_amount,"
                        + " credit_amount, currency, description, posted_at) values (?, ?, ?, 'ACC-A', ?, ?, 'INR', null, ?)",
                UUID.randomUUID().toString(), entryId.toString(), lineNumber,
                new BigDecimal(debit), new BigDecimal(credit), Timestamp.from(Instant.now()));
    }

    @Test
    void failureWhilePersistingLinesRollsBackTheWholeEntry() {
        JournalEntry entry = newEntry("TXN-ATOMIC");
        entry.addLine(1, "ACC-A", new BigDecimal("10.0000"), BigDecimal.ZERO.setScale(4), null);
        // Same line number twice violates the (entry_id, line_number) unique key on the second line insert.
        entry.addLine(1, "ACC-B", BigDecimal.ZERO.setScale(4), new BigDecimal("10.0000"), null);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.saveAndFlush(entry)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countEntries()).isZero();
        assertThat(countLines()).isZero();
    }

    @Test
    void databaseEnforcesUniqueBusinessReference() {
        repository.saveAndFlush(newEntry("TXN-UNIQUE"));

        assertThatThrownBy(() -> repository.saveAndFlush(newEntry("TXN-UNIQUE")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countEntries()).isEqualTo(1);
    }

    @Test
    void databaseEnforcesSingleReversalPerEntry() {
        JournalEntry original = repository.saveAndFlush(newEntry("TXN-ORIG"));
        insertReversal(original.getId(), "REV-1");

        assertThatThrownBy(() -> insertReversal(original.getId(), "REV-2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertReversal(UUID originalId, String reference) {
        jdbc.update("insert into journal_entries (id, business_reference, currency, description, status, entry_type,"
                        + " total_amount, reverses_entry_id, request_fingerprint, created_by, created_at)"
                        + " values (?, ?, 'INR', 'rev', 'POSTED', 'REVERSAL', 10.0000, ?, ?, 'tester', ?)",
                UUID.randomUUID().toString(), reference, originalId.toString(), "b".repeat(64),
                Timestamp.from(Instant.now()));
    }

    @Test
    void databaseRejectsLineWithBothSidesPositive() {
        UUID entryId = UUID.randomUUID();
        insertRawEntry(entryId, "TXN-CHK1");

        assertThatThrownBy(() -> insertRawLine(entryId, 1, "5.0000", "5.0000"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsLineWithBothSidesZeroOrNegative() {
        UUID entryId = UUID.randomUUID();
        insertRawEntry(entryId, "TXN-CHK2");

        assertThatThrownBy(() -> insertRawLine(entryId, 1, "0.0000", "0.0000"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRawLine(entryId, 2, "-1.0000", "0.0000"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsLineForUnknownEntry() {
        assertThatThrownBy(() -> insertRawLine(UUID.randomUUID(), 1, "5.0000", "0.0000"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsInconsistentReversalLinkAndZeroTotal() {
        assertThatThrownBy(() -> jdbc.update("insert into journal_entries (id, business_reference, currency,"
                        + " description, status, entry_type, total_amount, reverses_entry_id, request_fingerprint,"
                        + " created_by, created_at) values (?, 'TXN-BAD1', 'INR', 'x', 'POSTED', 'REVERSAL', 10.0000,"
                        + " null, ?, 't', ?)",
                UUID.randomUUID().toString(), "c".repeat(64), Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into journal_entries (id, business_reference, currency,"
                        + " description, status, entry_type, total_amount, reverses_entry_id, request_fingerprint,"
                        + " created_by, created_at) values (?, 'TXN-BAD2', 'INR', 'x', 'POSTED', 'NORMAL', 0.0000,"
                        + " null, ?, 't', ?)",
                UUID.randomUUID().toString(), "d".repeat(64), Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
