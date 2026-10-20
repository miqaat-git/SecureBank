-- =====================================================================
-- SecureBank Account Service - V1 baseline schema (MySQL 8.0, InnoDB)
--
-- UUID representation: BINARY(16) (RFC 4122 big-endian bytes, as written
-- by Hibernate). Read them in the mysql client with BIN_TO_UUID(id).
-- Money: DECIMAL(19,2). Currency is INR (2 decimal places).
-- Timestamps: DATETIME(6), always stored in UTC.
--
-- customer_id is a plain reference to a customer owned by Customer
-- Service. There is deliberately NO foreign key to another service's
-- database.
-- =====================================================================

CREATE TABLE accounts (
    id                BINARY(16)     NOT NULL,
    customer_id       BINARY(16)     NOT NULL,
    account_number    VARCHAR(20)    NOT NULL,
    account_type      VARCHAR(16)    NOT NULL,
    status            VARCHAR(16)    NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    available_balance DECIMAL(19,2)  NOT NULL DEFAULT 0.00,
    status_reason     VARCHAR(255)   NULL,
    status_changed_at DATETIME(6)    NULL,
    status_changed_by VARCHAR(100)   NULL,
    closed_at         DATETIME(6)    NULL,
    created_at        DATETIME(6)    NOT NULL,
    updated_at        DATETIME(6)    NOT NULL,
    version           BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT uq_accounts_account_number UNIQUE (account_number),

    CONSTRAINT ck_accounts_balance_non_negative CHECK (available_balance >= 0),
    CONSTRAINT ck_accounts_type   CHECK (account_type IN ('SAVINGS', 'CURRENT')),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT ck_accounts_currency CHECK (currency IN ('INR')),
    -- A closed account must have a zero balance and a closure timestamp; an open one must not.
    CONSTRAINT ck_accounts_closed_consistency CHECK (
        (status = 'CLOSED' AND closed_at IS NOT NULL AND available_balance = 0)
        OR (status <> 'CLOSED' AND closed_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_accounts_customer_id_created ON accounts (customer_id, created_at);
CREATE INDEX idx_accounts_status              ON accounts (status);
CREATE INDEX idx_accounts_type_status         ON accounts (account_type, status);
CREATE INDEX idx_accounts_created_at          ON accounts (created_at);

-- ---------------------------------------------------------------------
-- Idempotent record of every balance adjustment applied to an account.
-- This is NOT the accounting ledger (Ledger Service owns that). It only
-- guarantees that a retried Transaction Service call is applied once.
-- ---------------------------------------------------------------------
CREATE TABLE balance_operations (
    id                BINARY(16)     NOT NULL,
    account_id        BINARY(16)     NOT NULL,
    idempotency_key   VARCHAR(64)    NOT NULL,
    operation_type    VARCHAR(16)    NOT NULL,
    amount            DECIMAL(19,2)  NOT NULL,
    resulting_balance DECIMAL(19,2)  NOT NULL,
    reference_id      BINARY(16)     NOT NULL,
    created_at        DATETIME(6)    NOT NULL,
    created_by        VARCHAR(100)   NOT NULL,

    CONSTRAINT pk_balance_operations PRIMARY KEY (id),
    CONSTRAINT uq_balance_operations_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_balance_operations_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_balance_operations_type   CHECK (operation_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_balance_operations_amount CHECK (amount > 0),
    CONSTRAINT ck_balance_operations_result CHECK (resulting_balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_balance_operations_account_created ON balance_operations (account_id, created_at);
CREATE INDEX idx_balance_operations_reference       ON balance_operations (reference_id);
