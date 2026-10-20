package com.securebank.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.ledger.config.LedgerProperties;
import com.securebank.ledger.dto.JournalLineRequest;
import com.securebank.ledger.exception.InvalidJournalEntryException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure unit tests of the accounting rules; no Spring context. */
class JournalEntryValidatorTest {

    private final JournalEntryValidator validator = new JournalEntryValidator(new LedgerProperties(
            new LedgerProperties.Accounting(Set.of("INR"), 2, new BigDecimal("1000000"), 5),
            new LedgerProperties.Security(true, "")));

    private static JournalLineRequest line(int number, String account, String debit, String credit) {
        return new JournalLineRequest(number, account,
                debit == null ? null : new BigDecimal(debit),
                credit == null ? null : new BigDecimal(credit), null, null);
    }

    private static String codeOf(List<JournalLineRequest> lines, String currency, JournalEntryValidator v) {
        try {
            v.validate(currency, lines);
        } catch (InvalidJournalEntryException ex) {
            return ex.getCode();
        }
        return "NO_ERROR";
    }

    @Test
    void acceptsBalancedEntry() {
        assertThatCode(() -> validator.validate("INR", List.of(
                line(1, "ACC-A", "100.00", "0"), line(2, "ACC-B", "0", "100.00")))).doesNotThrowAnyException();
    }

    @Test
    void balanceUsesNumericComparisonNotScale() {
        assertThatCode(() -> validator.validate("INR", List.of(
                line(1, "ACC-A", "100.0", "0"), line(2, "ACC-B", "0", "100.00")))).doesNotThrowAnyException();
    }

    @Test
    void acceptsMultiLineEntryWithMissingZeroSide() {
        assertThatCode(() -> validator.validate("INR", List.of(
                line(1, "ACC-A", "60.50", null),
                line(2, "ACC-B", "39.50", null),
                line(3, "ACC-C", null, "100.00")))).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnbalancedEntryByTheSmallestUnit() {
        assertThatThrownBy(() -> validator.validate("INR", List.of(
                line(1, "ACC-A", "100.00", "0"), line(2, "ACC-B", "0", "99.99"))))
                .isInstanceOf(InvalidJournalEntryException.class)
                .extracting("code").isEqualTo("UNBALANCED_ENTRY");
    }

    @Test
    void rejectsFewerThanTwoLines() {
        assertThat(codeOf(List.of(line(1, "ACC-A", "10", "0")), "INR", validator)).isEqualTo("TOO_FEW_LINES");
        assertThat(codeOf(List.of(), "INR", validator)).isEqualTo("TOO_FEW_LINES");
        assertThat(codeOf(null, "INR", validator)).isEqualTo("TOO_FEW_LINES");
    }

    @Test
    void rejectsMoreThanConfiguredMaximumLines() {
        List<JournalLineRequest> lines = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            lines.add(i % 2 == 1 ? line(i, "ACC-A", "1", "0") : line(i, "ACC-B", "0", "1"));
        }
        assertThat(codeOf(lines, "INR", validator)).isEqualTo("TOO_MANY_LINES");
    }

    @Test
    void rejectsDuplicateLineNumbers() {
        assertThat(codeOf(List.of(line(1, "ACC-A", "10", "0"), line(1, "ACC-B", "0", "10")), "INR", validator))
                .isEqualTo("DUPLICATE_LINE_NUMBER");
    }

    @Test
    void rejectsLineWithBothSidesPositive() {
        assertThat(codeOf(List.of(line(1, "ACC-A", "10", "10"), line(2, "ACC-B", "0", "10")), "INR", validator))
                .isEqualTo("INVALID_LINE_SIDES");
    }

    @Test
    void rejectsLineWithBothSidesZeroOrMissing() {
        assertThat(codeOf(List.of(line(1, "ACC-A", "0", "0"), line(2, "ACC-B", "0", "10")), "INR", validator))
                .isEqualTo("INVALID_LINE_SIDES");
        assertThat(codeOf(List.of(line(1, "ACC-A", null, null), line(2, "ACC-B", "0", "10")), "INR", validator))
                .isEqualTo("INVALID_LINE_SIDES");
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThat(codeOf(List.of(line(1, "ACC-A", "-10", "0"), line(2, "ACC-B", "0", "-10")), "INR", validator))
                .isEqualTo("NEGATIVE_AMOUNT");
    }

    @Test
    void rejectsMoreDecimalPlacesThanConfiguredAndDoesNotRound() {
        assertThat(codeOf(List.of(line(1, "ACC-A", "10.001", "0"), line(2, "ACC-B", "0", "10.001")), "INR", validator))
                .isEqualTo("PRECISION_EXCEEDED");
    }

    @Test
    void trailingZerosBeyondScaleAreNotExtraPrecision() {
        assertThatCode(() -> validator.validate("INR", List.of(
                line(1, "ACC-A", "10.500000", "0"), line(2, "ACC-B", "0", "10.5")))).doesNotThrowAnyException();
    }

    @Test
    void rejectsAmountAboveConfiguredLimit() {
        assertThat(codeOf(List.of(line(1, "ACC-A", "1000000.01", "0"), line(2, "ACC-B", "0", "1000000.01")),
                "INR", validator)).isEqualTo("AMOUNT_LIMIT_EXCEEDED");
        assertThatCode(() -> validator.validate("INR", List.of(
                line(1, "ACC-A", "1000000", "0"), line(2, "ACC-B", "0", "1000000")))).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertThat(codeOf(List.of(line(1, "ACC-A", "10", "0"), line(2, "ACC-B", "0", "10")), "USD", validator))
                .isEqualTo("UNSUPPORTED_CURRENCY");
        assertThat(codeOf(List.of(line(1, "ACC-A", "10", "0"), line(2, "ACC-B", "0", "10")), null, validator))
                .isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void rejectsLineCurrencyDifferentFromEntryCurrency() {
        JournalLineRequest mismatched = new JournalLineRequest(1, "ACC-A", new BigDecimal("10"), BigDecimal.ZERO,
                "USD", null);
        assertThat(codeOf(List.of(mismatched, line(2, "ACC-B", "0", "10")), "INR", validator))
                .isEqualTo("LINE_CURRENCY_MISMATCH");
    }
}
