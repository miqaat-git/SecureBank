package com.securebank.transaction.service;

import java.math.BigDecimal;
import java.util.UUID;

/** An administrator's request to fund a customer account. The source is never part of it. */
public record FundingCommand(UUID targetAccountId, BigDecimal amount, String currency, String fundingReference,
                             String reason) {
}
