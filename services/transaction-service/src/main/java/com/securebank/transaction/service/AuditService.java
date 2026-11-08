package com.securebank.transaction.service;

import com.securebank.transaction.config.CorrelationIdFilter;
import com.securebank.transaction.domain.AuditEvent;
import com.securebank.transaction.domain.AuditEventRepository;
import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.security.Caller;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only audit writer. {@link #recordIndependent} always commits in its own transaction, so a denial, conflict
 * or failure is audited even though the business transaction rolled back or never started.
 * {@link #record} joins the caller's transaction, so an audit row and the state change it describes commit together.
 */
@Service
public class AuditService {

    private final AuditEventRepository events;
    private final Clock clock;

    public AuditService(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event) {
        events.save(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(AuditEvent event) {
        events.save(event);
    }

    public AuditEvent event(String type, String outcome, Caller caller, UUID transferId, UUID target,
                            BigDecimal amount, String currency, String reference, String reason, String detail) {
        return new AuditEvent(type, outcome, caller.subject(), caller.admin() ? "ADMIN" : "CUSTOMER", transferId,
                target, amount, currency, reference, reason, detail, MDC.get(CorrelationIdFilter.MDC_KEY),
                clock.instant());
    }

    /** Audit row describing a persisted funding transfer, attributed to the admin who created it. */
    public AuditEvent forFunding(String type, String outcome, Transfer t, String detail) {
        return new AuditEvent(type, outcome, t.getInitiatedBy(), "ADMIN", t.getId(), t.getDestinationAccountId(),
                t.getAmount(), t.getCurrency(), t.getFundingReference(), t.getFundingReason(), detail,
                MDC.get(CorrelationIdFilter.MDC_KEY), clock.instant());
    }
}
