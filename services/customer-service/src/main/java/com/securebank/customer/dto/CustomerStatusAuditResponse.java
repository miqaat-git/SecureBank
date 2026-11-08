package com.securebank.customer.dto;

import com.securebank.customer.entity.Customer;
import com.securebank.customer.entity.CustomerStatusAudit;
import java.time.LocalDateTime;
import java.util.UUID;

/** One audited status change (administrators only). */
public record CustomerStatusAuditResponse(
        UUID customerId,
        Customer.Status previousStatus,
        Customer.Status newStatus,
        String reason,
        String actorSubject,
        String actorRole,
        String correlationId,
        LocalDateTime occurredAt) {

    public static CustomerStatusAuditResponse from(CustomerStatusAudit a) {
        return new CustomerStatusAuditResponse(a.getCustomerId(), a.getPreviousStatus(), a.getNewStatus(),
                a.getReason(), a.getActorSubject(), a.getActorRole(), a.getCorrelationId(), a.getOccurredAt());
    }
}
