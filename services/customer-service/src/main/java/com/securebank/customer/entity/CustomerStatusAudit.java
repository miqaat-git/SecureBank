package com.securebank.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only record of one successful customer status change. Immutable: no setters, every column non-updatable,
 * and the repository exposes no update or delete operation. Holds no personal data beyond the customer id.
 */
@Entity
@Table(name = "customer_status_audit")
public class CustomerStatusAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "customer_id", nullable = false, updatable = false, length = 36)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, updatable = false, length = 20)
    private Customer.Status previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false, length = 20)
    private Customer.Status newStatus;

    @Column(name = "reason", nullable = false, updatable = false, length = 255)
    private String reason;

    @Column(name = "actor_subject", nullable = false, updatable = false, length = 100)
    private String actorSubject;

    @Column(name = "actor_role", nullable = false, updatable = false, length = 16)
    private String actorRole;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    protected CustomerStatusAudit() {
    }

    public CustomerStatusAudit(UUID customerId, Customer.Status previousStatus, Customer.Status newStatus,
                               String reason, String actorSubject, String actorRole, String correlationId,
                               LocalDateTime occurredAt) {
        this.customerId = customerId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.reason = reason;
        this.actorSubject = actorSubject;
        this.actorRole = actorRole;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public Customer.Status getPreviousStatus() { return previousStatus; }
    public Customer.Status getNewStatus() { return newStatus; }
    public String getReason() { return reason; }
    public String getActorSubject() { return actorSubject; }
    public String getActorRole() { return actorRole; }
    public String getCorrelationId() { return correlationId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
}
