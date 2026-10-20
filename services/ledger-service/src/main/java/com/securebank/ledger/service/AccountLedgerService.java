package com.securebank.ledger.service;

import com.securebank.ledger.config.LedgerConstants;
import com.securebank.ledger.dto.AccountLedgerLineResponse;
import com.securebank.ledger.dto.AccountMovementResponse;
import com.securebank.ledger.dto.PageResponse;
import com.securebank.ledger.entity.JournalLine;
import com.securebank.ledger.repository.AccountTotals;
import com.securebank.ledger.repository.JournalLineRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounting views over posted ledger lines for one account reference.
 *
 * <p>Convention (debit-positive): netMovement = sum(debitAmount) - sum(creditAmount). Ledger Service does
 * not know account types, so for a credit-normal account (e.g. a customer deposit liability) the
 * balance-increasing direction is the negative of netMovement. The result is an accounting view, not the
 * operational or available balance, which Account Service owns.
 */
@Service
@Transactional(readOnly = true)
public class AccountLedgerService {

    static final String CONVENTION =
            "netMovement = totalDebits - totalCredits over posted lines (debit-positive). "
                    + "Accounting view only; not the account's operational balance.";

    /** Open-ended bounds used when the caller omits from/to (keeps queries free of nullable parameters). */
    private static final Instant MIN_TIME = Instant.EPOCH;
    private static final Instant MAX_TIME = Instant.parse("9999-01-01T00:00:00Z");

    private final JournalLineRepository lines;

    public AccountLedgerService(JournalLineRepository lines) {
        this.lines = lines;
    }

    public PageResponse<AccountLedgerLineResponse> getLines(String accountReference, Instant from, Instant to,
                                                           int page, int size) {
        QuerySupport.requireAccountReference(accountReference);
        QuerySupport.requireValidRange(from, to);
        Pageable pageable = QuerySupport.pageable(page, size,
                Sort.by(Sort.Order.desc("postedAt"), Sort.Order.asc("lineNumber")));
        Page<JournalLine> result = lines.findByAccountReferenceAndPostedAtGreaterThanEqualAndPostedAtLessThan(
                accountReference, orDefault(from, MIN_TIME), orDefault(to, MAX_TIME), pageable);
        return PageResponse.from(result.map(JournalMapper::toAccountLine));
    }

    public AccountMovementResponse getMovement(String accountReference, String currency, Instant from, Instant to) {
        QuerySupport.requireAccountReference(accountReference);
        QuerySupport.requireValidRange(from, to);
        String effectiveCurrency = currency == null
                ? LedgerConstants.DEFAULT_CURRENCY
                : QuerySupport.requireCurrency(currency);

        AccountTotals totals = lines.totalsFor(accountReference, effectiveCurrency,
                orDefault(from, MIN_TIME), orDefault(to, MAX_TIME));

        BigDecimal debits = totals == null || totals.totalDebits() == null ? Money.ZERO : totals.totalDebits();
        BigDecimal credits = totals == null || totals.totalCredits() == null ? Money.ZERO : totals.totalCredits();
        long count = totals == null || totals.lineCount() == null ? 0L : totals.lineCount();

        return new AccountMovementResponse(accountReference, effectiveCurrency, from, to,
                debits, credits, debits.subtract(credits), count, CONVENTION);
    }

    private static Instant orDefault(Instant value, Instant fallback) {
        return value == null ? fallback : value;
    }
}
