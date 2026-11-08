package com.securebank.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only account-status history and audit record (D6): who changed the status (administrator subject or the
 * applying customer), from what to what, why, and under which correlation id. Rows are never updated or deleted.
 */
@Entity
@Table(name = "account_status_history")
public class AccountStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "account_id", nullable = false, updatable = false, length = 16)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "previous_status", updatable = false, length = 16)
    private AccountStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "new_status", nullable = false, updatable = false, length = 16)
    private AccountStatus newStatus;

    @Column(name = "reason", updatable = false, length = 255)
    private String reason;

    @Column(name = "actor_subject", nullable = false, updatable = false, length = 100)
    private String actorSubject;

    @Column(name = "actor_role", nullable = false, updatable = false, length = 16)
    private String actorRole;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AccountStatusHistory() {
    }

    public AccountStatusHistory(UUID accountId, AccountStatus previousStatus, AccountStatus newStatus, String reason,
                                String actorSubject, String actorRole, String correlationId, Instant occurredAt) {
        this.accountId = accountId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.reason = reason;
        this.actorSubject = actorSubject;
        this.actorRole = actorRole;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public AccountStatus getPreviousStatus() { return previousStatus; }
    public AccountStatus getNewStatus() { return newStatus; }
    public String getReason() { return reason; }
    public String getActorSubject() { return actorSubject; }
    public String getActorRole() { return actorRole; }
    public String getCorrelationId() { return correlationId; }
    public Instant getOccurredAt() { return occurredAt; }
}
