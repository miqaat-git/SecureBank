package com.securebank.ledger.support;

import com.securebank.ledger.dto.CreateJournalEntryRequest;
import com.securebank.ledger.dto.JournalLineRequest;
import java.math.BigDecimal;
import java.util.List;

public final class TestData {

    private TestData() {
    }

    /** A balanced two-line INR entry: debit one account, credit another, same amount. */
    public static CreateJournalEntryRequest entry(String reference, String debitAccount, String creditAccount,
                                                  String amount) {
        return new CreateJournalEntryRequest(reference, "INR", "Test entry " + reference, List.of(
                line(1, debitAccount, amount, "0", "debit leg"),
                line(2, creditAccount, "0", amount, "credit leg")));
    }

    public static JournalLineRequest line(int number, String account, String debit, String credit, String description) {
        return new JournalLineRequest(number, account, new BigDecimal(debit), new BigDecimal(credit), null, description);
    }

    public static CreateJournalEntryRequest withLines(String reference, JournalLineRequest... lines) {
        return new CreateJournalEntryRequest(reference, "INR", "Test entry " + reference, List.of(lines));
    }
}
