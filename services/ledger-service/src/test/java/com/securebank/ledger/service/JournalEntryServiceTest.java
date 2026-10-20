package com.securebank.ledger.service;

import static com.securebank.ledger.support.TestData.entry;
import static com.securebank.ledger.support.TestData.line;
import static com.securebank.ledger.support.TestData.withLines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.ledger.dto.CreateJournalEntryRequest;
import com.securebank.ledger.dto.JournalEntryResponse;
import com.securebank.ledger.dto.JournalEntrySummaryResponse;
import com.securebank.ledger.dto.JournalLineResponse;
import com.securebank.ledger.dto.PageResponse;
import com.securebank.ledger.dto.ReverseEntryRequest;
import com.securebank.ledger.enums.EntryStatus;
import com.securebank.ledger.enums.EntryType;
import com.securebank.ledger.exception.ConflictException;
import com.securebank.ledger.exception.InvalidJournalEntryException;
import com.securebank.ledger.exception.ResourceNotFoundException;
import com.securebank.ledger.support.AbstractLedgerTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JournalEntryServiceTest extends AbstractLedgerTest {

    @Autowired
    private JournalEntryService service;

    // ---------------------------------------------------------------- posting & retrieval

    @Test
    void postsBalancedEntryAndPersistsEntryAndLinesTogether() {
        PostingResult result = service.post(entry("TXN-0001", "ACC-A", "ACC-B", "1000.50"), "tester");

        assertThat(result.created()).isTrue();
        JournalEntryResponse posted = result.entry();
        assertThat(posted.status()).isEqualTo(EntryStatus.POSTED);
        assertThat(posted.entryType()).isEqualTo(EntryType.NORMAL);
        assertThat(posted.currency()).isEqualTo("INR");
        assertThat(posted.totalAmount()).isEqualByComparingTo("1000.50");
        assertThat(posted.createdBy()).isEqualTo("tester");
        assertThat(posted.createdAt()).isNotNull();
        assertThat(posted.lines()).hasSize(2);
        assertThat(countEntries()).isEqualTo(1);
        assertThat(countLines()).isEqualTo(2);
    }

    @Test
    void retrievesEntryByIdAndByBusinessReferenceWithOrderedLines() {
        JournalEntryResponse posted = service.post(entry("TXN-0002", "ACC-A", "ACC-B", "75.25"), "tester").entry();

        JournalEntryResponse byId = service.getById(posted.id());
        JournalEntryResponse byRef = service.getByBusinessReference("TXN-0002");

        assertThat(byId.id()).isEqualTo(posted.id());
        assertThat(byRef.id()).isEqualTo(posted.id());
        assertThat(byId.lines()).extracting(JournalLineResponse::lineNumber).containsExactly(1, 2);
        assertThat(byId.lines().get(0).accountReference()).isEqualTo("ACC-A");
        assertThat(byId.lines().get(0).debitAmount()).isEqualByComparingTo("75.25");
        assertThat(byId.lines().get(0).creditAmount()).isEqualByComparingTo("0");
        assertThat(byId.lines().get(1).creditAmount()).isEqualByComparingTo("75.25");
        assertThat(byId.reversedByEntryId()).isNull();
    }

    @Test
    void returnsLinesOfAnEntryAndNotFoundForUnknownEntry() {
        JournalEntryResponse posted = service.post(entry("TXN-0003", "ACC-A", "ACC-B", "10"), "tester").entry();

        assertThat(service.getLines(posted.id())).hasSize(2);
        assertThatThrownBy(() -> service.getLines(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getById(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getByBusinessReference("TXN-MISSING"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void supportsMultiLineEntries() {
        CreateJournalEntryRequest request = withLines("TXN-0004",
                line(1, "ACC-A", "60.00", "0", null),
                line(2, "ACC-B", "40.00", "0", null),
                line(3, "FEE-INCOME", "0", "5.00", "fee"),
                line(4, "ACC-C", "0", "95.00", null));

        JournalEntryResponse posted = service.post(request, "tester").entry();

        assertThat(posted.lines()).hasSize(4);
        assertThat(posted.totalAmount()).isEqualByComparingTo("100.00");
    }

    // ---------------------------------------------------------------- validation: nothing persisted

    @Test
    void unbalancedEntryIsRejectedAndNothingIsPersisted() {
        CreateJournalEntryRequest unbalanced = withLines("TXN-0005",
                line(1, "ACC-A", "100.00", "0", null), line(2, "ACC-B", "0", "99.00", null));

        assertThatThrownBy(() -> service.post(unbalanced, "tester"))
                .isInstanceOfSatisfying(InvalidJournalEntryException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("UNBALANCED_ENTRY"));
        assertThat(countEntries()).isZero();
        assertThat(countLines()).isZero();
    }

    @Test
    void invalidEntriesAreRejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.post(withLines("TXN-0006", line(1, "ACC-A", "10", "0", null)), "t"))
                .isInstanceOf(InvalidJournalEntryException.class);
        assertThatThrownBy(() -> service.post(withLines("TXN-0007",
                line(1, "ACC-A", "10.005", "0", null), line(2, "ACC-B", "0", "10.005", null)), "t"))
                .isInstanceOf(InvalidJournalEntryException.class);
        assertThat(countEntries()).isZero();
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    void identicalRetryReturnsOriginalAndCreatesNoDuplicate() {
        CreateJournalEntryRequest request = entry("TXN-0010", "ACC-A", "ACC-B", "250.00");

        PostingResult first = service.post(request, "tester");
        PostingResult retry = service.post(request, "tester");

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.entry().id()).isEqualTo(first.entry().id());
        assertThat(retry.entry().createdAt()).isEqualTo(first.entry().createdAt());
        assertThat(countEntries()).isEqualTo(1);
        assertThat(countLines()).isEqualTo(2);
    }

    @Test
    void retryWithEquivalentAmountScaleIsStillIdempotent() {
        PostingResult first = service.post(entry("TXN-0011", "ACC-A", "ACC-B", "100.0"), "tester");
        PostingResult retry = service.post(entry("TXN-0011", "ACC-A", "ACC-B", "100.00"), "tester");

        assertThat(retry.created()).isFalse();
        assertThat(retry.entry().id()).isEqualTo(first.entry().id());
    }

    @Test
    void reusingReferenceWithDifferentContentIsAConflict() {
        service.post(entry("TXN-0012", "ACC-A", "ACC-B", "100.00"), "tester");

        assertThatThrownBy(() -> service.post(entry("TXN-0012", "ACC-A", "ACC-B", "100.01"), "tester"))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("BUSINESS_REFERENCE_CONFLICT"));
        assertThatThrownBy(() -> service.post(entry("TXN-0012", "ACC-A", "ACC-C", "100.00"), "tester"))
                .isInstanceOf(ConflictException.class);
        assertThat(countEntries()).isEqualTo(1);
        assertThat(service.getByBusinessReference("TXN-0012").totalAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void concurrentIdenticalRequestsCreateExactlyOneEntry() throws Exception {
        int threads = 6;
        CreateJournalEntryRequest request = entry("TXN-0013", "ACC-A", "ACC-B", "500.00");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<PostingResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return service.post(request, "tester");
                }));
            }
            ready.await();
            go.countDown();

            List<PostingResult> results = new ArrayList<>();
            for (Future<PostingResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(results.stream().filter(PostingResult::created).count()).isEqualTo(1);
            assertThat(results.stream().map(r -> r.entry().id()).distinct().count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(countEntries()).isEqualTo(1);
        assertThat(countLines()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- reversal

    @Test
    void reversalMirrorsLinesLinksToOriginalAndNetsToZero() {
        JournalEntryResponse original = service.post(entry("TXN-0020", "ACC-A", "ACC-B", "300.00"), "tester").entry();

        PostingResult reversal = service.reverse(original.id(),
                new ReverseEntryRequest("REV-0020", "Posted in error"), "reverser");

        assertThat(reversal.created()).isTrue();
        JournalEntryResponse reversed = reversal.entry();
        assertThat(reversed.entryType()).isEqualTo(EntryType.REVERSAL);
        assertThat(reversed.reversesEntryId()).isEqualTo(original.id());
        assertThat(reversed.totalAmount()).isEqualByComparingTo("300.00");
        assertThat(reversed.description()).contains("TXN-0020").contains("Posted in error");
        assertThat(reversed.lines()).hasSize(2);
        // Debit and credit are swapped on the same account/line number.
        assertThat(reversed.lines().get(0).accountReference()).isEqualTo("ACC-A");
        assertThat(reversed.lines().get(0).debitAmount()).isEqualByComparingTo("0");
        assertThat(reversed.lines().get(0).creditAmount()).isEqualByComparingTo("300.00");
        assertThat(reversed.lines().get(1).accountReference()).isEqualTo("ACC-B");
        assertThat(reversed.lines().get(1).debitAmount()).isEqualByComparingTo("300.00");

        // The original is untouched but now reports who reversed it.
        JournalEntryResponse reloaded = service.getById(original.id());
        assertThat(reloaded.status()).isEqualTo(EntryStatus.POSTED);
        assertThat(reloaded.reversedByEntryId()).isEqualTo(reversed.id());
        assertThat(reloaded.lines().get(0).debitAmount()).isEqualByComparingTo("300.00");
        assertThat(countEntries()).isEqualTo(2);
    }

    @Test
    void identicalReversalRetryIsIdempotent() {
        JournalEntryResponse original = service.post(entry("TXN-0021", "ACC-A", "ACC-B", "20.00"), "t").entry();
        ReverseEntryRequest request = new ReverseEntryRequest("REV-0021", "duplicate");

        PostingResult first = service.reverse(original.id(), request, "r");
        PostingResult retry = service.reverse(original.id(), request, "r");

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.entry().id()).isEqualTo(first.entry().id());
        assertThat(countEntries()).isEqualTo(2);
    }

    @Test
    void secondReversalOfTheSameEntryIsRejected() {
        JournalEntryResponse original = service.post(entry("TXN-0022", "ACC-A", "ACC-B", "20.00"), "t").entry();
        service.reverse(original.id(), new ReverseEntryRequest("REV-0022", "first"), "r");

        assertThatThrownBy(() -> service.reverse(original.id(), new ReverseEntryRequest("REV-0022-B", "again"), "r"))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ENTRY_ALREADY_REVERSED"));
        // Same reference but different reason is also a different request.
        assertThatThrownBy(() -> service.reverse(original.id(), new ReverseEntryRequest("REV-0022", "other reason"), "r"))
                .isInstanceOf(ConflictException.class);
        assertThat(countEntries()).isEqualTo(2);
    }

    @Test
    void reversalEntryCannotBeReversed() {
        JournalEntryResponse original = service.post(entry("TXN-0023", "ACC-A", "ACC-B", "20.00"), "t").entry();
        JournalEntryResponse reversal = service.reverse(original.id(),
                new ReverseEntryRequest("REV-0023", "oops"), "r").entry();

        assertThatThrownBy(() -> service.reverse(reversal.id(), new ReverseEntryRequest("REV-0023-X", "undo"), "r"))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("REVERSAL_NOT_ALLOWED"));
    }

    @Test
    void reversalOfUnknownEntryIsNotFound() {
        assertThatThrownBy(() -> service.reverse(UUID.randomUUID(), new ReverseEntryRequest("REV-0024", "x"), "r"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reversalReferenceAlreadyUsedByAnotherEntryIsAConflict() {
        service.post(entry("TXN-0025", "ACC-A", "ACC-B", "20.00"), "t");
        JournalEntryResponse other = service.post(entry("TXN-0026", "ACC-A", "ACC-B", "30.00"), "t").entry();

        assertThatThrownBy(() -> service.reverse(other.id(), new ReverseEntryRequest("TXN-0025", "x"), "r"))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("BUSINESS_REFERENCE_CONFLICT"));
        assertThat(service.getById(other.id()).reversedByEntryId()).isNull();
    }

    @Test
    void concurrentIdenticalReversalsCreateExactlyOneReversal() throws Exception {
        JournalEntryResponse original = service.post(entry("TXN-0027", "ACC-A", "ACC-B", "20.00"), "t").entry();
        ReverseEntryRequest request = new ReverseEntryRequest("REV-0027", "race");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<PostingResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return service.reverse(original.id(), request, "r");
                }));
            }
            ready.await();
            go.countDown();

            List<PostingResult> results = new ArrayList<>();
            for (Future<PostingResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            assertThat(results.stream().filter(PostingResult::created).count()).isEqualTo(1);
            assertThat(results.stream().map(r -> r.entry().id()).distinct().count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(countEntries()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- listing & pagination

    @Test
    void listsEntriesWithPaginationAndFilters() {
        for (int i = 1; i <= 5; i++) {
            service.post(entry("TXN-010" + i, "ACC-A", "ACC-B", "10." + i + "0"), "tester");
        }

        PageResponse<JournalEntrySummaryResponse> firstPage = service.list(null, null, null, null, 0, 2);
        PageResponse<JournalEntrySummaryResponse> lastPage = service.list(null, null, null, null, 2, 2);

        assertThat(firstPage.content()).hasSize(2);
        assertThat(firstPage.totalElements()).isEqualTo(5);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(lastPage.content()).hasSize(1);

        assertThat(service.list(null, "TXN-0103", null, null, 0, 10).content())
                .extracting(JournalEntrySummaryResponse::businessReference).containsExactly("TXN-0103");
        assertThat(service.list(EntryStatus.POSTED, null, null, null, 0, 10).totalElements()).isEqualTo(5);
    }

    @Test
    void filtersEntriesByDateRangeWithExclusiveUpperBound() {
        JournalEntryResponse posted = service.post(entry("TXN-0200", "ACC-A", "ACC-B", "10"), "tester").entry();
        Instant created = posted.createdAt();

        assertThat(service.list(null, null, created.minusSeconds(60), created.plusSeconds(60), 0, 10).totalElements())
                .isEqualTo(1);
        assertThat(service.list(null, null, created, null, 0, 10).totalElements()).isEqualTo(1);
        assertThat(service.list(null, null, null, created, 0, 10).totalElements()).isZero();
        assertThat(service.list(null, null, created.plusSeconds(1), null, 0, 10).totalElements()).isZero();
    }
}
