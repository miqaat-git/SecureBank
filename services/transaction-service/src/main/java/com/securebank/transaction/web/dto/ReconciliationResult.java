package com.securebank.transaction.web.dto;

/**
 * MATCH: Account balance equals the ledger-derived balance.
 * DISCREPANCY: confirmed difference (no unfinished transfer explains it).
 * INCOMPLETE: cannot be confirmed either way (see reasonCode); never reported as a match.
 */
public enum ReconciliationResult { MATCH, DISCREPANCY, INCOMPLETE }
