package com.securebank.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persistent transfer aggregate. All status changes go through {@link #transitionTo}, which enforces the
 * state machine. Lease columns (lock_owner / lock_expires_at) are managed only by bulk updates in the
 * repository; @DynamicUpdate guarantees ordinary saves never overwrite them.
 */
@Entity
@Table(name = "transfer")
@DynamicUpdate
public class Transfer {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_account_id", nullable = false, updatable = false, length = 36)
    private UUID sourceAccountId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "destination_account_id", nullable = false, updatable = false, length = 36)
    private UUID destinationAccountId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TransferStatus status;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 255)
    private String errorMessage;

    @Column(name = "initiated_by", nullable = false, updatable = false, length = 128)
    private String initiatedBy;

    @Column(name = "initiator_customer_id", nullable = false, updatable = false, length = 64)
    private String initiatorCustomerId;

    @Column(name = "validation_attempts", nullable = false)
    private int validationAttempts;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "lock_owner", length = 64)
    private String lockOwner;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "lock_expires_at")
    private Instant lockExpiresAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Transfer() {
    }

    public static Transfer initiate(UUID id, String idempotencyKey, String requestFingerprint, UUID sourceAccountId,
                                    UUID destinationAccountId, BigDecimal amount, String currency,
                                    String initiatedBy, String initiatorCustomerId, Instant now) {
        Transfer t = new Transfer();
        t.id = id;
        t.idempotencyKey = idempotencyKey;
        t.requestFingerprint = requestFingerprint;
        t.sourceAccountId = sourceAccountId;
        t.destinationAccountId = destinationAccountId;
        t.amount = amount;
        t.currency = currency;
        t.status = TransferStatus.INITIATED;
        t.initiatedBy = initiatedBy;
        t.initiatorCustomerId = initiatorCustomerId;
        t.validationAttempts = 0;
        t.nextAttemptAt = now;
        t.createdAt = now;
        t.updatedAt = now;
        return t;
    }

    /**
     * Applies a state transition. Throws {@link IllegalStateTransitionException} if not allowed;
     * terminal states can therefore never be reset or overwritten.
     */
    public void transitionTo(TransferStatus target, String errorCode, Instant now) {
        TransferStateMachine.requireAllowed(this.status, target);
        this.status = target;
        this.updatedAt = now;
        if (target == TransferStatus.COMPLETED) {
            this.completedAt = now;
            this.errorCode = null;
            this.errorMessage = null;
        } else if (errorCode != null) {
            this.errorCode = errorCode;
            this.errorMessage = FailureMessages.describe(errorCode);
        }
        if (target.isTerminal() || target == TransferStatus.RECONCILIATION_REQUIRED) {
            this.nextAttemptAt = null;
        } else {
            this.nextAttemptAt = now;
        }
    }

    public int incrementValidationAttempts(Instant now) {
        this.validationAttempts++;
        this.updatedAt = now;
        return this.validationAttempts;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public TransferStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public String getInitiatedBy() { return initiatedBy; }
    public String getInitiatorCustomerId() { return initiatorCustomerId; }
    public int getValidationAttempts() { return validationAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getLockExpiresAt() { return lockExpiresAt; }
    public String getLockOwner() { return lockOwner; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getVersion() { return version; }
}
