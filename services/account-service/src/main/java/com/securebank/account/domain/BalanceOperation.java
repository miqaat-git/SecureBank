package com.securebank.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Immutable record of one balance adjustment applied to an account. The unique idempotency key guarantees that a
 * retried request from the Transaction Service is applied at most once. This is not the accounting ledger.
 */
@jakarta.persistence.Entity
@Table(name = "balance_operations",
        uniqueConstraints = @UniqueConstraint(name = "uq_balance_operations_idempotency_key",
                columnNames = "idempotency_key"),
        indexes = {
                @Index(name = "idx_balance_operations_account_created", columnList = "account_id, created_at"),
                @Index(name = "idx_balance_operations_reference", columnList = "reference_id")
        })
public class BalanceOperation implements Persistable<UUID> {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false, length = 16)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "account_id", nullable = false, updatable = false, length = 16)
    private UUID accountId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "operation_type", nullable = false, updatable = false, length = 16)
    private BalanceOperationType operationType;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "resulting_balance", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal resultingBalance;

    /** Identifier of the business transaction (Transaction Service) this adjustment belongs to. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "reference_id", nullable = false, updatable = false, length = 16)
    private UUID referenceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @Transient
    private boolean newEntity = true;

    protected BalanceOperation() {
    }

    private BalanceOperation(UUID accountId, String idempotencyKey, BalanceOperationType operationType,
                             BigDecimal amount, BigDecimal resultingBalance, UUID referenceId, Instant now,
                             String createdBy) {
        this.id = UUID.randomUUID();
        this.accountId = accountId;
        this.idempotencyKey = idempotencyKey;
        this.operationType = operationType;
        this.amount = amount;
        this.resultingBalance = resultingBalance;
        this.referenceId = referenceId;
        this.createdAt = now;
        this.createdBy = createdBy;
    }

    public static BalanceOperation record(UUID accountId, String idempotencyKey, BalanceOperationType type,
                                          BigDecimal amount, BigDecimal resultingBalance, UUID referenceId,
                                          Instant now, String createdBy) {
        return new BalanceOperation(accountId, idempotencyKey, type, amount, resultingBalance, referenceId, now,
                createdBy);
    }

    /** True when this operation was requested for the same account, type, amount and reference. */
    public boolean matches(UUID otherAccountId, BalanceOperationType otherType, BigDecimal otherAmount,
                           UUID otherReferenceId) {
        return accountId.equals(otherAccountId)
                && operationType == otherType
                && amount.compareTo(otherAmount) == 0
                && referenceId.equals(otherReferenceId);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BalanceOperationType getOperationType() {
        return operationType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getResultingBalance() {
        return resultingBalance;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
