package com.securebank.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One durable workflow step. The operation id is generated once and reused for every retry. */
@Entity
@Table(name = "transfer_operation")
public class TransferOperation {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "operation_id", nullable = false, updatable = false, length = 36)
    private UUID operationId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "transfer_id", nullable = false, updatable = false, length = 36)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, updatable = false, length = 32)
    private OperationType operationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_service", nullable = false, updatable = false, length = 32)
    private TargetService targetService;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OperationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "result_code", length = 64)
    private String resultCode;

    @Column(name = "last_error", length = 255)
    private String lastError;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected TransferOperation() {
    }

    public static TransferOperation create(UUID transferId, OperationType type, Instant now) {
        TransferOperation o = new TransferOperation();
        o.operationId = UUID.randomUUID();
        o.transferId = transferId;
        o.operationType = type;
        o.targetService = type.targetService();
        o.idempotencyKey = transferId + ":" + type.name();
        o.status = OperationStatus.PENDING;
        o.attemptCount = 0;
        o.createdAt = now;
        o.updatedAt = now;
        return o;
    }

    public void beginAttempt(Instant now) {
        this.attemptCount++;
        this.updatedAt = now;
    }

    /** Records a definitive remote outcome. A final status is never changed again. */
    public void markFinal(OperationStatus finalStatus, String code, Instant now) {
        if (!finalStatus.isFinal()) {
            throw new IllegalArgumentException("Not a final status: " + finalStatus);
        }
        if (this.status.isFinal()) {
            return;
        }
        this.status = finalStatus;
        this.resultCode = code;
        this.lastError = finalStatus == OperationStatus.REJECTED ? code : null;
        this.updatedAt = now;
    }

    /** Records an inconclusive attempt; ambiguous outcomes make the status UNKNOWN (sticky). */
    public void markInconclusive(boolean ambiguous, String code, Instant now) {
        if (this.status.isFinal()) {
            return;
        }
        if (ambiguous) {
            this.status = OperationStatus.UNKNOWN;
        }
        this.resultCode = code;
        this.lastError = code;
        this.updatedAt = now;
    }

    public void resetAttempts(Instant now) {
        if (!this.status.isFinal()) {
            this.attemptCount = 0;
            this.updatedAt = now;
        }
    }

    public UUID getOperationId() { return operationId; }
    public UUID getTransferId() { return transferId; }
    public OperationType getOperationType() { return operationType; }
    public TargetService getTargetService() { return targetService; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public OperationStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public String getResultCode() { return resultCode; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
