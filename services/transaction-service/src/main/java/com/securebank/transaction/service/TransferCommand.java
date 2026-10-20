package com.securebank.transaction.service;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferCommand(UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount, String currency) {
}
