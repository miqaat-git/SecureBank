package com.securebank.transaction.web.dto;

import com.securebank.transaction.domain.TransferHistory;
import com.securebank.transaction.domain.TransferStatus;
import java.time.Instant;

public record TransferHistoryResponse(TransferStatus previousStatus, TransferStatus newStatus, String eventCode,
                                      String reason, Instant occurredAt) {

    public static TransferHistoryResponse from(TransferHistory h) {
        return new TransferHistoryResponse(h.getPreviousStatus(), h.getNewStatus(), h.getEventCode(), h.getReason(),
                h.getOccurredAt());
    }
}
