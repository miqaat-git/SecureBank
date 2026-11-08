package com.securebank.transaction.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Read-only reconciliation view. generatedAt/ledgerReadAt/correlationId make a report traceable. Pagination is over
 * accounts (ordered by id); the optional result filter narrows the rows of the requested account page only.
 */
public record ReconciliationReport(Instant generatedAt, Instant ledgerReadAt, String correlationId,
                                   String basis, int page, int size, long totalAccounts, int totalPages,
                                   ReconciliationSummary summary, List<AccountReconciliationRow> rows) {
}
