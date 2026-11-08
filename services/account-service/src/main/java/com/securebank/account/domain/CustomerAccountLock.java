package com.securebank.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** Per-customer lock row; see V3__customer_account_locks.sql. Carries no business data. */
@jakarta.persistence.Entity
@Table(name = "customer_account_locks")
public class CustomerAccountLock implements Persistable<UUID> {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", nullable = false, updatable = false, length = 16)
    private UUID customerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected CustomerAccountLock() {
    }

    public CustomerAccountLock(UUID customerId, Instant createdAt) {
        this.customerId = customerId;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return customerId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
