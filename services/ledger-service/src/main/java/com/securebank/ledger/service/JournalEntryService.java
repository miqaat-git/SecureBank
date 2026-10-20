package com.securebank.ledger.service;

import com.securebank.ledger.dto.CreateJournalEntryRequest;
import com.securebank.ledger.dto.JournalEntryResponse;
import com.securebank.ledger.dto.JournalEntrySummaryResponse;
import com.securebank.ledger.dto.JournalLineRequest;
import com.securebank.ledger.dto.JournalLineResponse;
import com.securebank.ledger.dto.PageResponse;
import com.securebank.ledger.dto.ReverseEntryRequest;
import com.securebank.ledger.entity.JournalEntry;
import com.securebank.ledger.entity.JournalLine;
import com.securebank.ledger.enums.EntryStatus;
import com.securebank.ledger.enums.EntryType;
import com.securebank.ledger.exception.ConflictException;
import com.securebank.ledger.exception.ResourceNotFoundException;
import com.securebank.ledger.repository.JournalEntryRepository;
import com.securebank.ledger.repository.JournalEntrySpecifications;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posts and reads journal entries.
 *
 * <p>Write idempotency: the unique index on {@code business_reference} (and on {@code reverses_entry_id})
 * is the source of truth. The service first looks for an existing entry; if none, it inserts the entry and
 * all lines in ONE transaction. If a concurrent request wins the race, the insert fails with a
 * unique-constraint violation, the failed transaction is rolled back completely, and the service then
 * re-reads the winner in a fresh transaction and replays or rejects it.
 *
 * <p>This protects ledger writes against duplicates. It is not exactly-once delivery across services:
 * orchestration and delivery retries belong to Transaction Service.
 */
@Service
public class JournalEntryService {

    private final JournalEntryRepository entries;
    private final JournalEntryValidator validator;
    private final Clock clock;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public JournalEntryService(JournalEntryRepository entries, JournalEntryValidator validator, Clock clock,
                               PlatformTransactionManager transactionManager) {
        this.entries = entries;
        this.validator = validator;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    // ------------------------------------------------------------------ writes

    public PostingResult post(CreateJournalEntryRequest request, String actor) {
        validator.validate(request.currency(), request.lines());
        String fingerprint = RequestFingerprint.forEntry(request);

        Optional<PostingResult> replay = findReplay(request.businessReference(), fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            JournalEntryResponse created = writeTx.execute(status -> {
                JournalEntry entry = buildEntry(request, fingerprint, actor);
                return JournalMapper.toResponse(entries.saveAndFlush(entry), null);
            });
            return new PostingResult(created, true);
        } catch (DataIntegrityViolationException ex) {
            // Lost a race against an identical/conflicting request, or violated a DB constraint.
            return findReplay(request.businessReference(), fingerprint).orElseThrow(() -> ex);
        }
    }

    public PostingResult reverse(UUID originalEntryId, ReverseEntryRequest request, String actor) {
        String fingerprint = RequestFingerprint.forReversal(originalEntryId, request.businessReference(),
                request.reason());

        Optional<PostingResult> replay = checkReversal(originalEntryId, request.businessReference(), fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            JournalEntryResponse created = writeTx.execute(status -> {
                JournalEntry original = entries.findById(originalEntryId)
                        .orElseThrow(() -> notFound(originalEntryId));
                JournalEntry reversal = buildReversal(original, request, fingerprint, actor);
                return JournalMapper.toResponse(entries.saveAndFlush(reversal), null);
            });
            return new PostingResult(created, true);
        } catch (DataIntegrityViolationException ex) {
            return checkReversal(originalEntryId, request.businessReference(), fingerprint)
                    .orElseThrow(() -> ex);
        }
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public JournalEntryResponse getById(UUID id) {
        JournalEntry entry = entries.findById(id).orElseThrow(() -> notFound(id));
        return JournalMapper.toResponse(entry, reversedBy(entry));
    }

    @Transactional(readOnly = true)
    public JournalEntryResponse getByBusinessReference(String businessReference) {
        QuerySupport.requireBusinessReference(businessReference);
        JournalEntry entry = entries.findByBusinessReference(businessReference)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No journal entry with business reference " + businessReference));
        return JournalMapper.toResponse(entry, reversedBy(entry));
    }

    @Transactional(readOnly = true)
    public List<JournalLineResponse> getLines(UUID entryId) {
        JournalEntry entry = entries.findById(entryId).orElseThrow(() -> notFound(entryId));
        return entry.getLines().stream().map(JournalMapper::toLineResponse).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<JournalEntrySummaryResponse> list(EntryStatus status, String reference,
                                                          Instant from, Instant to, int page, int size) {
        QuerySupport.requireValidRange(from, to);
        if (reference != null && !reference.isBlank()) {
            QuerySupport.requireBusinessReference(reference);
        }
        Pageable pageable = QuerySupport.pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<JournalEntry> result = entries.findAll(
                JournalEntrySpecifications.matching(status, reference, from, to), pageable);
        return PageResponse.from(result.map(JournalMapper::toSummary));
    }

    // ------------------------------------------------------------------ internals

    /** Returns the stored result if an equivalent request exists, throws on a conflicting one, else empty. */
    private Optional<PostingResult> findReplay(String businessReference, String fingerprint) {
        return readTx.execute(status -> entries.findByBusinessReference(businessReference).map(existing -> {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new ConflictException("BUSINESS_REFERENCE_CONFLICT",
                        "Business reference " + businessReference
                                + " was already used for a different request");
            }
            return new PostingResult(JournalMapper.toResponse(existing, reversedBy(existing)), false);
        }));
    }

    /** Eligibility + idempotency checks for a reversal. Empty means "go ahead and create it". */
    private Optional<PostingResult> checkReversal(UUID originalId, String reversalReference, String fingerprint) {
        return readTx.execute(status -> {
            JournalEntry original = entries.findById(originalId).orElseThrow(() -> notFound(originalId));
            if (original.getEntryType() == EntryType.REVERSAL) {
                throw new ConflictException("REVERSAL_NOT_ALLOWED", "A reversal entry cannot itself be reversed");
            }
            Optional<JournalEntry> existingReversal = entries.findByReversesEntryId(originalId);
            if (existingReversal.isPresent()) {
                JournalEntry reversal = existingReversal.get();
                if (reversal.getRequestFingerprint().equals(fingerprint)) {
                    return Optional.of(new PostingResult(JournalMapper.toResponse(reversal, null), false));
                }
                throw new ConflictException("ENTRY_ALREADY_REVERSED",
                        "Entry " + originalId + " has already been reversed by a different request");
            }
            if (entries.findByBusinessReference(reversalReference).isPresent()) {
                throw new ConflictException("BUSINESS_REFERENCE_CONFLICT",
                        "Business reference " + reversalReference + " is already used by another entry");
            }
            return Optional.empty();
        });
    }

    private JournalEntry buildEntry(CreateJournalEntryRequest request, String fingerprint, String actor) {
        BigDecimal total = request.lines().stream()
                .map(line -> Money.normalize(line.debitAmount()))
                .reduce(Money.ZERO, BigDecimal::add);
        JournalEntry entry = new JournalEntry(request.businessReference(), request.currency(),
                request.description().trim(), EntryType.NORMAL, null, total, fingerprint, actor, now());
        request.lines().stream()
                .sorted(Comparator.comparingInt(JournalLineRequest::lineNumber))
                .forEach(line -> entry.addLine(line.lineNumber(), line.accountReference(),
                        Money.normalize(line.debitAmount()), Money.normalize(line.creditAmount()),
                        trimToNull(line.description())));
        return entry;
    }

    /** A reversal mirrors every line of the original with debit and credit swapped. */
    private JournalEntry buildReversal(JournalEntry original, ReverseEntryRequest request,
                                       String fingerprint, String actor) {
        String description = "Reversal of " + original.getBusinessReference() + ": " + request.reason().trim();
        JournalEntry reversal = new JournalEntry(request.businessReference(), original.getCurrency(),
                description, EntryType.REVERSAL, original.getId(), original.getTotalAmount(),
                fingerprint, actor, now());
        for (JournalLine line : original.getLines()) {
            reversal.addLine(line.getLineNumber(), line.getAccountReference(),
                    line.getCreditAmount(), line.getDebitAmount(), line.getDescription());
        }
        return reversal;
    }

    private UUID reversedBy(JournalEntry entry) {
        return entries.findByReversesEntryId(entry.getId()).map(JournalEntry::getId).orElse(null);
    }

    private Instant now() {
        // MySQL DATETIME(6) stores microseconds; truncate so what we return equals what is stored.
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("Journal entry " + id + " was not found");
    }
}
