-- SecureBank Ledger Service - initial schema (V1).
--
-- Target: MySQL 8.0 (InnoDB, utf8mb4 database default). The same script is executed against H2 in
-- MySQL compatibility mode by the automated tests, so only portable DDL is used here.
-- The schema `securebank_ledger` itself is created by the DBA script in docs/database/ because the
-- service's JDBC URL must point at an existing schema (see README, "Database").
--
-- NEVER edit this file after it has been applied anywhere. Add V2__..., V3__... instead.

CREATE TABLE journal_entries (
    id                  CHAR(36)       NOT NULL,
    business_reference  VARCHAR(100)   NOT NULL,
    currency            VARCHAR(3)     NOT NULL,
    description         VARCHAR(500)   NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    entry_type          VARCHAR(20)    NOT NULL,
    total_amount        DECIMAL(19,4)  NOT NULL,
    reverses_entry_id   CHAR(36)       NULL,
    request_fingerprint VARCHAR(64)    NOT NULL,
    created_by          VARCHAR(200)   NOT NULL,
    created_at          DATETIME(6)    NOT NULL,
    CONSTRAINT pk_journal_entries PRIMARY KEY (id),
    -- Idempotency anchor: one journal entry per business event, enforced by the database.
    CONSTRAINT uk_journal_entries_business_reference UNIQUE (business_reference),
    -- At most one reversal per original entry, enforced by the database.
    CONSTRAINT uk_journal_entries_reverses_entry_id UNIQUE (reverses_entry_id),
    CONSTRAINT fk_journal_entries_reverses FOREIGN KEY (reverses_entry_id) REFERENCES journal_entries (id),
    CONSTRAINT ck_journal_entries_status CHECK (status IN ('POSTED')),
    CONSTRAINT ck_journal_entries_type CHECK (entry_type IN ('NORMAL', 'REVERSAL')),
    CONSTRAINT ck_journal_entries_total CHECK (total_amount > 0),
    CONSTRAINT ck_journal_entries_reversal_link CHECK (
        (entry_type = 'REVERSAL' AND reverses_entry_id IS NOT NULL)
        OR (entry_type = 'NORMAL' AND reverses_entry_id IS NULL)
    )
);

CREATE INDEX ix_journal_entries_created_at ON journal_entries (created_at);
CREATE INDEX ix_journal_entries_status_created_at ON journal_entries (status, created_at);

CREATE TABLE journal_lines (
    id                CHAR(36)       NOT NULL,
    entry_id          CHAR(36)       NOT NULL,
    line_number       INT            NOT NULL,
    account_reference VARCHAR(64)    NOT NULL,
    debit_amount      DECIMAL(19,4)  NOT NULL,
    credit_amount     DECIMAL(19,4)  NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    description       VARCHAR(255)   NULL,
    -- Copy of journal_entries.created_at so account/date queries can use a single index.
    posted_at         DATETIME(6)    NOT NULL,
    CONSTRAINT pk_journal_lines PRIMARY KEY (id),
    CONSTRAINT uk_journal_lines_entry_line UNIQUE (entry_id, line_number),
    CONSTRAINT fk_journal_lines_entry FOREIGN KEY (entry_id) REFERENCES journal_entries (id),
    CONSTRAINT ck_journal_lines_line_number CHECK (line_number >= 1),
    CONSTRAINT ck_journal_lines_non_negative CHECK (debit_amount >= 0 AND credit_amount >= 0),
    -- Exactly one side is positive; the other side is zero.
    CONSTRAINT ck_journal_lines_one_side CHECK (
        (debit_amount > 0 AND credit_amount = 0)
        OR (debit_amount = 0 AND credit_amount > 0)
    )
);

CREATE INDEX ix_journal_lines_account_posted_at ON journal_lines (account_reference, posted_at);
