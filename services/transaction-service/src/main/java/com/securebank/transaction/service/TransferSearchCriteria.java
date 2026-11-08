package com.securebank.transaction.service;

import com.securebank.transaction.domain.TransferStatus;
import java.time.Instant;
import java.util.UUID;

public record TransferSearchCriteria(TransferStatus status, UUID sourceAccountId, UUID destinationAccountId,
                                     Instant createdFrom, Instant createdTo) {
}
