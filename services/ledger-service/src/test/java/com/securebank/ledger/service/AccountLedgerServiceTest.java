package com.securebank.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.securebank.ledger.dto.AccountMovementResponse;
import com.securebank.ledger.exception.InvalidQueryException;
import com.securebank.ledger.repository.AccountTotals;
import com.securebank.ledger.repository.JournalLineRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Mockito unit tests of the net-movement convention and input validation. */
@ExtendWith(MockitoExtension.class)
class AccountLedgerServiceTest {

    @Mock
    private JournalLineRepository lines;

    @Test
    void netMovementIsDebitsMinusCredits() {
        when(lines.totalsFor(eq("ACC-A"), eq("INR"), any(Instant.class), any(Instant.class)))
                .thenReturn(new AccountTotals(new BigDecimal("150.0000"), new BigDecimal("40.0000"), 3L));

        AccountMovementResponse response = new AccountLedgerService(lines).getMovement("ACC-A", null, null, null);

        assertThat(response.totalDebits()).isEqualByComparingTo("150");
        assertThat(response.totalCredits()).isEqualByComparingTo("40");
        assertThat(response.netMovement()).isEqualByComparingTo("110");
        assertThat(response.lineCount()).isEqualTo(3);
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.convention()).contains("totalDebits - totalCredits");
    }

    @Test
    void creditHeavyAccountHasNegativeNetMovement() {
        when(lines.totalsFor(eq("ACC-B"), eq("INR"), any(Instant.class), any(Instant.class)))
                .thenReturn(new AccountTotals(new BigDecimal("10.0000"), new BigDecimal("70.0000"), 2L));

        assertThat(new AccountLedgerService(lines).getMovement("ACC-B", "INR", null, null).netMovement())
                .isEqualByComparingTo("-60");
    }

    @Test
    void accountWithNoLinesHasZeroMovement() {
        when(lines.totalsFor(eq("ACC-Z"), eq("INR"), any(Instant.class), any(Instant.class)))
                .thenReturn(new AccountTotals(null, null, 0L));

        AccountMovementResponse response = new AccountLedgerService(lines).getMovement("ACC-Z", "INR", null, null);

        assertThat(response.netMovement()).isEqualByComparingTo("0");
        assertThat(response.lineCount()).isZero();
    }

    @Test
    void passesExplicitDateRangeToRepository() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");
        when(lines.totalsFor("ACC-A", "INR", from, to)).thenReturn(new AccountTotals(null, null, 0L));

        new AccountLedgerService(lines).getMovement("ACC-A", "INR", from, to);

        verify(lines).totalsFor("ACC-A", "INR", from, to);
    }

    @Test
    void rejectsInvalidInputWithoutTouchingTheDatabase() {
        AccountLedgerService service = new AccountLedgerService(lines);
        Instant from = Instant.parse("2026-02-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> service.getMovement("bad ref!", null, null, null))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> service.getMovement("ACC-A", null, from, to))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> service.getMovement("ACC-A", "inr", null, null))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> service.getLines("ACC-A", null, null, -1, 20))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> service.getLines("ACC-A", null, null, 0, 201))
                .isInstanceOf(InvalidQueryException.class);
        verifyNoInteractions(lines);
    }
}
