package com.securebank.ledger.entity;

import com.securebank.ledger.enums.EntryStatus;
import com.securebank.ledger.enums.EntryType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * A posted journal entry. Immutable: Hibernate never issues UPDATE for it and the runtime database
 * account has no UPDATE/DELETE privilege. Corrections are made with a REVERSAL entry.
 */
@Entity
@Immutable
@Table(name = "journal_entries")
public class JournalEntry implements Persistable<UUID> {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    @Column(name = "business_reference", nullable = false, updatable = false, length = 100)
    private String businessReference;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "description", nullable = false, updatable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, updatable = false, length = 20)
    private EntryStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "entry_type", nullable = false, updatable = false, length = 20)
    private EntryType entryType;

    @Column(name = "total_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "reverses_entry_id", updatable = false, length = 36)
    private UUID reversesEntryId;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_by", nullable = false, updatable = false, length = 200)
    private String createdBy;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    @OrderBy("lineNumber ASC")
    private List<JournalLine> lines = new ArrayList<>();

    @Transient
    private boolean newEntity = true;

    protected JournalEntry() {
        // required by JPA
    }

    public JournalEntry(String businessReference, String currency, String description, EntryType entryType,
                        UUID reversesEntryId, BigDecimal totalAmount, String requestFingerprint,
                        String createdBy, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.businessReference = businessReference;
        this.currency = currency;
        this.description = description;
        this.status = EntryStatus.POSTED;
        this.entryType = entryType;
        this.reversesEntryId = reversesEntryId;
        this.totalAmount = totalAmount;
        this.requestFingerprint = requestFingerprint;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /** Adds a line to a not-yet-persisted entry. The line inherits currency and posting time. */
    public JournalLine addLine(int lineNumber, String accountReference, BigDecimal debitAmount,
                               BigDecimal creditAmount, String lineDescription) {
        JournalLine line = new JournalLine(this, lineNumber, accountReference, debitAmount, creditAmount,
                lineDescription);
        lines.add(line);
        return line;
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

    public String getBusinessReference() {
        return businessReference;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public EntryStatus getStatus() {
        return status;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public UUID getReversesEntryId() {
        return reversesEntryId;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<JournalLine> getLines() {
        return lines;
    }
}
