package com.securebank.ledger.service;

import com.securebank.ledger.config.LedgerProperties;
import com.securebank.ledger.dto.JournalLineRequest;
import com.securebank.ledger.exception.InvalidJournalEntryException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Enforces the accounting rules that cannot be expressed per-field:
 * supported currency, 2..N lines, unique line numbers, non-negative amounts within configured precision
 * and limits, exactly one positive side per line, and exact balance (total debits == total credits).
 * Never rounds or adjusts amounts to make an entry balance.
 */
@Component
public class JournalEntryValidator {

    private final LedgerProperties.Accounting rules;

    public JournalEntryValidator(LedgerProperties properties) {
        this.rules = properties.accounting();
    }

    public void validate(String currency, List<JournalLineRequest> lines) {
        if (currency == null || !rules.supportedCurrencies().contains(currency)) {
            throw new InvalidJournalEntryException("UNSUPPORTED_CURRENCY",
                    "Currency is not supported; supported: " + rules.supportedCurrencies());
        }
        if (lines == null || lines.size() < 2) {
            throw new InvalidJournalEntryException("TOO_FEW_LINES",
                    "A journal entry needs at least two lines");
        }
        if (lines.size() > rules.maxLinesPerEntry()) {
            throw new InvalidJournalEntryException("TOO_MANY_LINES",
                    "A journal entry may have at most " + rules.maxLinesPerEntry() + " lines");
        }

        Set<Integer> lineNumbers = new HashSet<>();
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        for (JournalLineRequest line : lines) {
            if (!lineNumbers.add(line.lineNumber())) {
                throw new InvalidJournalEntryException("DUPLICATE_LINE_NUMBER",
                        "Line number " + line.lineNumber() + " is used more than once");
            }
            if (line.currency() != null && !line.currency().equals(currency)) {
                throw new InvalidJournalEntryException("LINE_CURRENCY_MISMATCH",
                        "Line " + line.lineNumber() + " currency differs from the entry currency");
            }
            BigDecimal debit = orZero(line.debitAmount());
            BigDecimal credit = orZero(line.creditAmount());
            checkAmount(line.lineNumber(), "debitAmount", debit);
            checkAmount(line.lineNumber(), "creditAmount", credit);

            boolean debitPositive = debit.signum() > 0;
            boolean creditPositive = credit.signum() > 0;
            if (debitPositive == creditPositive) {
                throw new InvalidJournalEntryException("INVALID_LINE_SIDES",
                        "Line " + line.lineNumber() + " must have exactly one positive side (debit or credit)");
            }
            totalDebits = totalDebits.add(debit);
            totalCredits = totalCredits.add(credit);
        }

        // Exact decimal comparison: compareTo ignores scale (10.0 vs 10.00), equals would not.
        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new InvalidJournalEntryException("UNBALANCED_ENTRY",
                    "Total debits (" + totalDebits.toPlainString() + ") must equal total credits ("
                            + totalCredits.toPlainString() + ")");
        }
    }

    private void checkAmount(Integer lineNumber, String field, BigDecimal amount) {
        if (amount.signum() < 0) {
            throw new InvalidJournalEntryException("NEGATIVE_AMOUNT",
                    "Line " + lineNumber + " " + field + " must not be negative");
        }
        if (amount.stripTrailingZeros().scale() > rules.maxAmountScale()) {
            throw new InvalidJournalEntryException("PRECISION_EXCEEDED",
                    "Line " + lineNumber + " " + field + " has more than " + rules.maxAmountScale()
                            + " decimal places");
        }
        if (amount.compareTo(rules.maxLineAmount()) > 0) {
            throw new InvalidJournalEntryException("AMOUNT_LIMIT_EXCEEDED",
                    "Line " + lineNumber + " " + field + " exceeds the maximum of "
                            + rules.maxLineAmount().toPlainString());
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
