package com.securebank.ledger.service;

import static com.securebank.ledger.support.TestData.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.securebank.ledger.dto.AccountLedgerLineResponse;
import com.securebank.ledger.dto.AccountMovementResponse;
import com.securebank.ledger.dto.JournalEntryResponse;
import com.securebank.ledger.dto.PageResponse;
import com.securebank.ledger.dto.ReverseEntryRequest;
import com.securebank.ledger.support.AbstractLedgerTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Account-ledger queries against the real schema (H2 in MySQL mode). */
class AccountLedgerQueriesTest extends AbstractLedgerTest {

    @Autowired
    private JournalEntryService entries;

    @Autowired
    private AccountLedgerService accounts;

    private JournalEntryResponse firstEntry;

    @BeforeEach
    void seed() {
        // ACC-A: debit 1000, then credit 300  -> net +700.   ACC-B: the mirror image -> net -700.
        firstEntry = entries.post(entry("TXN-1001", "ACC-A", "ACC-B", "1000.00"), "t").entry();
        entries.post(entry("TXN-1002", "ACC-B", "ACC-A", "300.00"), "t");
        entries.post(entry("TXN-1003", "ACC-C", "ACC-D", "50.00"), "t");
    }

    @Test
    void netMovementIsDebitsMinusCreditsOfPostedLines() {
        AccountMovementResponse movement = accounts.getMovement("ACC-A", "INR", null, null);

        assertThat(movement.totalDebits()).isEqualByComparingTo("1000.00");
        assertThat(movement.totalCredits()).isEqualByComparingTo("300.00");
        assertThat(movement.netMovement()).isEqualByComparingTo("700.00");
        assertThat(movement.lineCount()).isEqualTo(2);
        assertThat(accounts.getMovement("ACC-B", null, null, null).netMovement()).isEqualByComparingTo("-700.00");
    }

    @Test
    void unknownAccountHasZeroMovement() {
        AccountMovementResponse movement = accounts.getMovement("ACC-NONE", "INR", null, null);
        assertThat(movement.netMovement()).isEqualByComparingTo("0");
        assertThat(movement.lineCount()).isZero();
    }

    @Test
    void movementRespectsDateRange() {
        Instant created = firstEntry.createdAt();
        assertThat(accounts.getMovement("ACC-A", "INR", created.plusSeconds(3600), null).lineCount()).isZero();
        assertThat(accounts.getMovement("ACC-A", "INR", null, created.minusSeconds(3600)).lineCount()).isZero();
        assertThat(accounts.getMovement("ACC-A", "INR", created.minusSeconds(3600), created.plusSeconds(3600))
                .lineCount()).isEqualTo(2);
    }

    @Test
    void reversalCancelsOutMovement() {
        entries.reverse(firstEntry.id(), new ReverseEntryRequest("REV-1001", "error"), "r");

        // ACC-A: debits 1000, credits 300 + 1000 (reversal) -> net -300 (only the second entry remains).
        AccountMovementResponse movement = accounts.getMovement("ACC-A", "INR", null, null);
        assertThat(movement.netMovement()).isEqualByComparingTo("-300.00");
        assertThat(movement.lineCount()).isEqualTo(3);
    }

    @Test
    void accountLinesArePaginatedAndCarryEntryReference() {
        PageResponse<AccountLedgerLineResponse> firstPage = accounts.getLines("ACC-A", null, null, 0, 1);
        PageResponse<AccountLedgerLineResponse> secondPage = accounts.getLines("ACC-A", null, null, 1, 1);

        assertThat(firstPage.totalElements()).isEqualTo(2);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.content()).hasSize(1);
        assertThat(secondPage.content()).hasSize(1);
        assertThat(firstPage.content().get(0).lineId()).isNotEqualTo(secondPage.content().get(0).lineId());
        assertThat(firstPage.content().get(0).accountReference()).isEqualTo("ACC-A");
        assertThat(firstPage.content().get(0).businessReference()).startsWith("TXN-100");
    }

    @Test
    void accountLinesRespectDateRange() {
        Instant created = firstEntry.createdAt();
        assertThat(accounts.getLines("ACC-A", created.plusSeconds(3600), null, 0, 10).totalElements()).isZero();
        assertThat(accounts.getLines("ACC-A", created.minusSeconds(3600), created.plusSeconds(3600), 0, 10)
                .totalElements()).isEqualTo(2);
    }
}
