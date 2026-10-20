package com.securebank.account.domain;

import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Account aggregate. Account Service owns the account record, its status and the account-level balance state.
 *
 * <p>Business rules (all enforced here, in the domain object, so no caller can bypass them):
 * <ul>
 *   <li>The balance is only changed through {@link #credit} and {@link #debit}; there is no setter.</li>
 *   <li>The balance can never become negative (no overdraft policy) or exceed {@link Money#MAX_AMOUNT}.</li>
 *   <li>Only ACTIVE accounts accept credits and debits; FROZEN and CLOSED accounts reject them.</li>
 *   <li>CLOSED is terminal and requires a zero balance.</li>
 *   <li>{@code version} (optimistic locking) guarantees that concurrent balance changes are never silently
 *       overwritten: the second writer gets an optimistic-locking failure and must re-read.</li>
 * </ul>
 *
 * <p>Identity fields (id, customerId, accountNumber, type, currency, createdAt) are immutable after creation.
 */
@jakarta.persistence.Entity
@Table(name = "accounts",
        uniqueConstraints = @UniqueConstraint(name = "uq_accounts_account_number", columnNames = "account_number"),
        indexes = {
                @Index(name = "idx_accounts_customer_id_created", columnList = "customer_id, created_at"),
                @Index(name = "idx_accounts_status", columnList = "status"),
                @Index(name = "idx_accounts_type_status", columnList = "account_type, status"),
                @Index(name = "idx_accounts_created_at", columnList = "created_at")
        })
public class Account {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false, length = 16)
    private UUID id;

    /** Reference to the customer owned by Customer Service. No cross-database foreign key. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", nullable = false, updatable = false, length = 16)
    private UUID customerId;

    @Column(name = "account_number", nullable = false, updatable = false, length = 20)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "account_type", nullable = false, updatable = false, length = 16)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "available_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal availableBalance;

    @Column(name = "status_reason", length = 255)
    private String statusReason;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "status_changed_by", length = 100)
    private String statusChangedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Required by JPA. */
    protected Account() {
    }

    private Account(UUID id, UUID customerId, String accountNumber, AccountType accountType, String currency,
                    Instant now) {
        this.id = id;
        this.customerId = customerId;
        this.accountNumber = accountNumber;
        this.accountType = accountType;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
        this.availableBalance = Money.ZERO;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Opens a new ACTIVE account with a zero balance. Opening deposits are not supported. */
    public static Account open(UUID customerId, String accountNumber, AccountType accountType, String currency,
                               Instant now) {
        return new Account(UUID.randomUUID(), customerId, accountNumber, accountType, currency, now);
    }

    // ------------------------------------------------------------------ balance operations

    /** Adds a positive amount. Only allowed on ACTIVE accounts. */
    public void credit(BigDecimal amount, Instant now) {
        requireOperational();
        BigDecimal normalized = Money.normalizePositive(amount);
        BigDecimal newBalance = availableBalance.add(normalized);
        if (newBalance.compareTo(Money.MAX_AMOUNT) > 0) {
            throw new ApiException(ErrorCode.BALANCE_LIMIT_EXCEEDED, "The resulting balance exceeds the maximum.");
        }
        this.availableBalance = newBalance;
        this.updatedAt = now;
    }

    /** Subtracts a positive amount. Only allowed on ACTIVE accounts and never below zero. */
    public void debit(BigDecimal amount, Instant now) {
        requireOperational();
        BigDecimal normalized = Money.normalizePositive(amount);
        if (availableBalance.compareTo(normalized) < 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS, "Insufficient available balance.");
        }
        this.availableBalance = availableBalance.subtract(normalized);
        this.updatedAt = now;
    }

    // ------------------------------------------------------------------ status transitions

    public void freeze(String reason, String actor, Instant now) {
        if (status != AccountStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_ACCOUNT_STATE,
                    "Only an ACTIVE account can be frozen (current status: " + status + ").");
        }
        changeStatus(AccountStatus.FROZEN, reason, actor, now);
    }

    public void unfreeze(String reason, String actor, Instant now) {
        if (status != AccountStatus.FROZEN) {
            throw new ApiException(ErrorCode.INVALID_ACCOUNT_STATE,
                    "Only a FROZEN account can be unfrozen (current status: " + status + ").");
        }
        changeStatus(AccountStatus.ACTIVE, reason, actor, now);
    }

    /**
     * Closes the account. Closure conditions: status is ACTIVE (a frozen account must be unfrozen first, so that
     * a freeze cannot be bypassed by closing) and the balance is exactly zero. Closure is permanent.
     */
    public void close(String actor, Instant now) {
        if (status == AccountStatus.CLOSED) {
            throw new ApiException(ErrorCode.INVALID_ACCOUNT_STATE, "The account is already closed.");
        }
        if (status == AccountStatus.FROZEN) {
            throw new ApiException(ErrorCode.ACCOUNT_CLOSURE_NOT_ALLOWED,
                    "A frozen account cannot be closed. It must be unfrozen by an administrator first.");
        }
        if (availableBalance.signum() != 0) {
            throw new ApiException(ErrorCode.ACCOUNT_CLOSURE_NOT_ALLOWED,
                    "The account balance must be zero before the account can be closed.");
        }
        changeStatus(AccountStatus.CLOSED, "ACCOUNT_CLOSED", actor, now);
        this.closedAt = now;
    }

    private void changeStatus(AccountStatus newStatus, String reason, String actor, Instant now) {
        this.status = newStatus;
        this.statusReason = reason;
        this.statusChangedBy = actor;
        this.statusChangedAt = now;
        this.updatedAt = now;
    }

    private void requireOperational() {
        if (status != AccountStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_NOT_OPERATIONAL,
                    "The account is " + status + " and cannot be used for balance operations.");
        }
    }

    // ------------------------------------------------------------------ getters (no setters on purpose)

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    public String getStatusChangedBy() {
        return statusChangedBy;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
