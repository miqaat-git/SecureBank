package com.securebank.ledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** One debit-or-credit line of a journal entry. Immutable once persisted. */
@Entity
@Immutable
@Table(name = "journal_lines")
public class JournalLine implements Persistable<UUID> {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false, updatable = false)
    private JournalEntry entry;

    @Column(name = "line_number", nullable = false, updatable = false)
    private int lineNumber;

    @Column(name = "account_reference", nullable = false, updatable = false, length = 64)
    private String accountReference;

    @Column(name = "debit_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal debitAmount;

    @Column(name = "credit_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal creditAmount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "description", updatable = false, length = 255)
    private String description;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    @Transient
    private boolean newEntity = true;

    protected JournalLine() {
        // required by JPA
    }

    JournalLine(JournalEntry entry, int lineNumber, String accountReference, BigDecimal debitAmount,
                BigDecimal creditAmount, String description) {
        this.id = UUID.randomUUID();
        this.entry = entry;
        this.lineNumber = lineNumber;
        this.accountReference = accountReference;
        this.debitAmount = debitAmount;
        this.creditAmount = creditAmount;
        this.currency = entry.getCurrency();
        this.description = description;
        this.postedAt = entry.getCreatedAt();
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public JournalEntry getEntry() {
        return entry;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getAccountReference() {
        return accountReference;
    }

    public BigDecimal getDebitAmount() {
        return debitAmount;
    }

    public BigDecimal getCreditAmount() {
        return creditAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
}
