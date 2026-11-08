package com.securebank.transaction.domain;

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

/** Append-only record of one state transition. */
@Entity
@Table(name = "transfer_history")
public class TransferHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "transfer_id", nullable = false, updatable = false, length = 36)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", updatable = false, length = 32)
    private TransferStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false, length = 32)
    private TransferStatus newStatus;

    @Column(name = "event_code", nullable = false, updatable = false, length = 64)
    private String eventCode;

    @Column(name = "reason", updatable = false, length = 255)
    private String reason;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected TransferHistory() {
    }

    public TransferHistory(UUID transferId, TransferStatus previousStatus, TransferStatus newStatus,
                           String eventCode, String reason, Instant occurredAt) {
        this.transferId = transferId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.eventCode = eventCode;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public UUID getTransferId() { return transferId; }
    public TransferStatus getPreviousStatus() { return previousStatus; }
    public TransferStatus getNewStatus() { return newStatus; }
    public String getEventCode() { return eventCode; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
}
