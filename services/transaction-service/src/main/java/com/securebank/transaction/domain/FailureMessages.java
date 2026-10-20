package com.securebank.transaction.domain;

import java.util.Map;

/**
 * Maps machine codes to fixed, safe, human-readable texts. Remote error text is never stored or returned.
 */
public final class FailureMessages {

    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("SOURCE_ACCOUNT_NOT_FOUND", "The source account was not found."),
            Map.entry("DESTINATION_ACCOUNT_NOT_FOUND", "The destination account was not found."),
            Map.entry("SOURCE_ACCOUNT_INELIGIBLE", "The source account is not eligible for transfers."),
            Map.entry("DESTINATION_ACCOUNT_INELIGIBLE", "The destination account is not eligible to receive transfers."),
            Map.entry("SOURCE_ACCOUNT_NOT_OWNED", "The source account does not belong to the requesting customer."),
            Map.entry("CURRENCY_MISMATCH", "The transfer currency does not match an account currency."),
            Map.entry("INSUFFICIENT_FUNDS", "The source account has insufficient available funds."),
            Map.entry("DEPENDENCY_UNAVAILABLE", "A required service was unavailable; no funds were moved."),
            Map.entry("LEDGER_POSTING_REJECTED", "The ledger rejected the journal entry; the debit is being reversed."),
            Map.entry("DESTINATION_CREDIT_REJECTED", "The destination credit was rejected; the transfer is being reversed."),
            Map.entry("OUTCOME_UNKNOWN", "A downstream outcome could not be established; reconciliation is required."),
            Map.entry("COMPENSATION_FAILED", "A compensating step could not be completed; manual reconciliation is required."),
            Map.entry("DEBIT_REJECTED", "The source debit was rejected."));

    private static final String GENERIC = "The operation was rejected by a downstream service.";

    private FailureMessages() {
    }

    public static String describe(String code) {
        if (code == null) {
            return null;
        }
        return MESSAGES.getOrDefault(code, GENERIC);
    }
}
