package com.securebank.transaction.client;

import java.math.BigDecimal;
import java.util.UUID;

public record LedgerEntryLine(UUID accountId, String direction, BigDecimal amount) {
}
