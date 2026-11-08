package com.securebank.transaction.domain;

import java.util.UUID;

/**
 * Funding has no external source (Blueprint D5/D7: no gateway, no clearing account, no settlement provider). Each
 * funding event is posted to the ledger as a balanced entry against this fixed, ledger-only contra reference; it is
 * not an Account Service account and never has a balance operation.
 */
public final class FundingSource {

    public static final UUID CONTRA_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000f00d");

    private FundingSource() {
    }
}
