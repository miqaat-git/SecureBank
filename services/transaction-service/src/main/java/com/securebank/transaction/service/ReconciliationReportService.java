package com.securebank.transaction.service;

import com.securebank.transaction.client.AccountBalancePage;
import com.securebank.transaction.client.AccountBalanceReader;
import com.securebank.transaction.client.AccountBalanceRow;
import com.securebank.transaction.client.LedgerBalanceReader;
import com.securebank.transaction.client.LedgerBalanceRow;
import com.securebank.transaction.client.LedgerBalances;
import com.securebank.transaction.client.RemoteCallException;
import com.securebank.transaction.config.CorrelationIdFilter;
import com.securebank.transaction.domain.TransferStatus;
import com.securebank.transaction.exception.ApiException;
import com.securebank.transaction.repository.TransferRepository;
import com.securebank.transaction.security.Caller;
import com.securebank.transaction.web.dto.AccountReconciliationRow;
import com.securebank.transaction.web.dto.ReconciliationReport;
import com.securebank.transaction.web.dto.ReconciliationResult;
import com.securebank.transaction.web.dto.ReconciliationSummary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only Account-to-Ledger comparison. It reads through the services' internal APIs only (no cross-service
 * database access), writes nothing anywhere, never corrects a balance and never creates a balancing entry.
 *
 * <p>Ledger semantics (inspected): every entry is POSTED; a reversal is an ordinary entry that mirrors each original
 * line with debit and credit swapped, so summing every line nets a reversal against its original exactly once.
 * Customer accounts are liabilities: ledgerBalance = credits - debits in the account's currency.
 */
@Service
@Transactional(readOnly = true)
public class ReconciliationReportService {

    public static final int MAX_PAGE_SIZE = 100;
    static final String BASIS = "ledgerBalance = sum(credits) - sum(debits) of posted ledger lines in the account "
            + "currency; difference = accountBalance - ledgerBalance";

    private static final Logger log = LoggerFactory.getLogger(ReconciliationReportService.class);

    private final AccountBalanceReader accounts;
    private final LedgerBalanceReader ledger;
    private final TransferRepository transfers;
    private final Clock clock;

    public ReconciliationReportService(AccountBalanceReader accounts, LedgerBalanceReader ledger,
                                       TransferRepository transfers, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.transfers = transfers;
        this.clock = clock;
    }

    public ReconciliationReport report(Caller caller, String status, String currency, UUID accountId,
                                       ReconciliationResult resultFilter, int page, int size) {
        if (caller == null || !caller.admin()) {
            throw ApiException.forbidden("FORBIDDEN", "Administrator role required.");
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("VALIDATION_FAILED",
                    "page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE + ".");
        }
        String accountStatus = blankToNull(status) == null ? null : status.trim().toUpperCase(Locale.ROOT);
        String accountCurrency = blankToNull(currency) == null ? null : currency.trim().toUpperCase(Locale.ROOT);

        AccountBalancePage accountPage;
        try {
            accountPage = accounts.list(accountStatus, accountCurrency, accountId, page, size);
        } catch (RemoteCallException e) {
            log.warn("reconciliation view: account service unavailable ({})", e.getCode());
            throw ApiException.unavailable("ACCOUNT_SERVICE_UNAVAILABLE",
                    "Account Service could not be consulted; the reconciliation view is unavailable.");
        }
        List<AccountBalanceRow> accountRows = accountPage.content();

        Map<String, List<LedgerBalanceRow>> ledgerByAccount = new HashMap<>();
        Set<UUID> unfinished = new HashSet<>();
        Instant ledgerReadAt = null;
        if (!accountRows.isEmpty()) {
            List<UUID> ids = accountRows.stream().map(AccountBalanceRow::accountId).toList();
            LedgerBalances balances;
            try {
                balances = ledger.balances(ids);
            } catch (RemoteCallException e) {
                log.warn("reconciliation view: ledger service unavailable ({})", e.getCode());
                throw ApiException.unavailable("LEDGER_SERVICE_UNAVAILABLE",
                        "Ledger Service could not be consulted; the reconciliation view is unavailable.");
            }
            ledgerReadAt = balances.generatedAt();
            for (LedgerBalanceRow row : balances.balances()) {
                ledgerByAccount.computeIfAbsent(row.accountId().toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(row);
            }
            for (Object[] pair : transfers.findAccountPairsOfUnfinished(ids,
                    TransferStatus.unfinishedStatuses())) {
                unfinished.add((UUID) pair[0]);
                unfinished.add((UUID) pair[1]);
            }
        }

        List<AccountReconciliationRow> all = new ArrayList<>();
        for (AccountBalanceRow a : accountRows) {
            all.add(compare(a, ledgerByAccount.getOrDefault(a.accountId().toString().toUpperCase(Locale.ROOT),
                    List.of()), unfinished.contains(a.accountId())));
        }
        ReconciliationSummary summary = new ReconciliationSummary(count(all, ReconciliationResult.MATCH),
                count(all, ReconciliationResult.DISCREPANCY), count(all, ReconciliationResult.INCOMPLETE));
        List<AccountReconciliationRow> rows = resultFilter == null ? all
                : all.stream().filter(r -> r.result() == resultFilter).toList();

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        log.info("reconciliation view generated by {} page={} size={} matches={} discrepancies={} incomplete={}",
                caller.subject(), page, size, summary.matches(), summary.discrepancies(), summary.incomplete());
        return new ReconciliationReport(clock.instant(), ledgerReadAt, correlationId, BASIS, accountPage.page(),
                accountPage.size(), accountPage.totalElements(), accountPage.totalPages(), summary, rows);
    }

    static AccountReconciliationRow compare(AccountBalanceRow account, List<LedgerBalanceRow> ledgerRows,
                                            boolean hasUnfinishedTransfers) {
        String currency = account.currency();
        BigDecimal accountBalance = account.availableBalance() == null ? BigDecimal.ZERO : account.availableBalance();
        BigDecimal ledgerBalance = BigDecimal.ZERO;
        long lines = 0;
        boolean otherCurrency = false;
        for (LedgerBalanceRow row : ledgerRows) {
            if (row.currency() != null && row.currency().equalsIgnoreCase(currency)) {
                ledgerBalance = ledgerBalance.add(row.creditMinusDebit());
                lines += row.lineCount();
            } else {
                otherCurrency = true;
            }
        }
        BigDecimal difference = accountBalance.subtract(ledgerBalance);
        ReconciliationResult result;
        String reason = null;
        if (otherCurrency) {
            result = ReconciliationResult.INCOMPLETE;
            reason = "LEDGER_HAS_OTHER_CURRENCY_LINES";
        } else if (difference.signum() == 0) {
            result = ReconciliationResult.MATCH;
        } else if (hasUnfinishedTransfers) {
            result = ReconciliationResult.INCOMPLETE;
            reason = "UNFINISHED_TRANSFERS";
        } else {
            result = ReconciliationResult.DISCREPANCY;
            reason = lines == 0 ? "NO_LEDGER_ENTRIES" : "BALANCE_MISMATCH";
        }
        return new AccountReconciliationRow(account.accountId(), currency,
                account.status(), accountBalance, ledgerBalance, difference, result, reason, lines, account.asOf());
    }

    private static long count(List<AccountReconciliationRow> rows, ReconciliationResult result) {
        return rows.stream().filter(r -> r.result() == result).count();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
