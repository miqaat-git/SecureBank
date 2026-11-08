package com.securebank.transaction.web.dto;

/** Counts over the accounts of the returned page (before any result filter). */
public record ReconciliationSummary(long matches, long discrepancies, long incomplete) {
}
