package com.securebank.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only audit record (no setters, all columns non-updatable). */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, updatable = false, length = 48)
    private String eventType;

    @Column(name = "outcome", nullable = false, updatable = false, length = 16)
    private String outcome;

    @Column(name = "actor_subject", nullable = false, updatable = false, length = 128)
    private String actorSubject;

    @Column(name = "actor_role", nullable = false, updatable = false, length = 16)
    private String actorRole;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "transfer_id", updatable = false, length = 36)
    private UUID transferId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "target_account", updatable = false, length = 36)
    private UUID targetAccount;

    @Column(name = "amount", updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", updatable = false, length = 3)
    private String currency;

    @Column(name = "reference", updatable = false, length = 64)
    private String reference;

    @Column(name = "reason", updatable = false, length = 255)
    private String reason;

    @Column(name = "detail_code", updatable = false, length = 64)
    private String detailCode;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditEvent() {
    }

    public AuditEvent(String eventType, String outcome, String actorSubject, String actorRole, UUID transferId,
                      UUID targetAccount, BigDecimal amount, String currency, String reference, String reason,
                      String detailCode, String correlationId, Instant occurredAt) {
        this.eventType = eventType;
        this.outcome = outcome;
        this.actorSubject = actorSubject;
        this.actorRole = actorRole;
        this.transferId = transferId;
        this.targetAccount = targetAccount;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
        this.reason = reason == null ? null : (reason.length() > 255 ? reason.substring(0, 255) : reason);
        this.detailCode = detailCode;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public String getEventType() { return eventType; }
    public String getOutcome() { return outcome; }
    public String getActorSubject() { return actorSubject; }
    public String getActorRole() { return actorRole; }
    public UUID getTransferId() { return transferId; }
    public UUID getTargetAccount() { return targetAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReference() { return reference; }
    public String getReason() { return reason; }
    public String getDetailCode() { return detailCode; }
    public String getCorrelationId() { return correlationId; }
    public Instant getOccurredAt() { return occurredAt; }
}
