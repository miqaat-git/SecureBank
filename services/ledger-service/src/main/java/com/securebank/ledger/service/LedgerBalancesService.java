package com.securebank.ledger.service;

import com.securebank.ledger.dto.LedgerBalanceLine;
import com.securebank.ledger.dto.LedgerBalancesResponse;
import com.securebank.ledger.exception.InvalidQueryException;
import com.securebank.ledger.repository.AccountCurrencyTotals;
import com.securebank.ledger.repository.JournalLineRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only totals for the Transaction Service reconciliation view. Never writes, never corrects. */
@Service
@Transactional(readOnly = true)
public class LedgerBalancesService {

    public static final int MAX_ACCOUNTS = 100;

    private final JournalLineRepository lines;
    private final Clock clock;

    public LedgerBalancesService(JournalLineRepository lines, Clock clock) {
        this.lines = lines;
        this.clock = clock;
    }

    public LedgerBalancesResponse balances(List<String> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            throw new InvalidQueryException("At least one account id is required.");
        }
        if (accountIds.size() > MAX_ACCOUNTS) {
            throw new InvalidQueryException("At most " + MAX_ACCOUNTS + " account ids per request.");
        }
        Set<String> references = new LinkedHashSet<>();
        for (String id : accountIds) {
            try {
                references.add(InternalJournalTranslator.accountReference(UUID.fromString(id.trim())));
            } catch (IllegalArgumentException e) {
                throw new InvalidQueryException("Account ids must be UUIDs.");
            }
        }
        List<LedgerBalanceLine> result = lines.totalsByAccount(references).stream()
                .map(LedgerBalancesService::toLine)
                .sorted(Comparator.comparing(LedgerBalanceLine::accountId).thenComparing(LedgerBalanceLine::currency))
                .toList();
        return new LedgerBalancesResponse(clock.instant(), result);
    }

    private static LedgerBalanceLine toLine(AccountCurrencyTotals t) {
        BigDecimal debits = t.totalDebits() == null ? Money.ZERO : t.totalDebits();
        BigDecimal credits = t.totalCredits() == null ? Money.ZERO : t.totalCredits();
        return new LedgerBalanceLine(t.accountReference(), t.currency(), debits, credits, credits.subtract(debits),
                t.lineCount() == null ? 0L : t.lineCount());
    }
}
