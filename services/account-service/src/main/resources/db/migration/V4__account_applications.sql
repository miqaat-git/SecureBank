-- D6 account application approval: PENDING and REJECTED statuses, an append-only status history/audit table and a
-- database guarantee of at most one PENDING application per customer and account type.
-- Established migrations V1-V3 are untouched.

ALTER TABLE accounts DROP CHECK ck_accounts_status;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_status
    CHECK (status IN ('PENDING', 'REJECTED', 'ACTIVE', 'FROZEN', 'CLOSED'));

-- Non-NULL only while an application is PENDING; the unique index then allows one such row per customer and type.
ALTER TABLE accounts ADD COLUMN pending_slot VARCHAR(48)
    GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN CONCAT(HEX(customer_id), ':', account_type) ELSE NULL END) STORED;
CREATE UNIQUE INDEX uq_accounts_one_pending_per_customer_type ON accounts (pending_slot);

CREATE TABLE account_status_history (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    account_id      BINARY(16)   NOT NULL,
    previous_status VARCHAR(16)  NULL,
    new_status      VARCHAR(16)  NOT NULL,
    reason          VARCHAR(255) NULL,
    actor_subject   VARCHAR(100) NOT NULL,
    actor_role      VARCHAR(16)  NOT NULL,
    correlation_id  VARCHAR(64)  NULL,
    occurred_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_account_status_history PRIMARY KEY (id),
    CONSTRAINT fk_account_status_history_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_account_status_history_new CHECK (new_status IN ('PENDING', 'REJECTED', 'ACTIVE', 'FROZEN', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_account_status_history_account ON account_status_history (account_id, id);

-- Accounts that existed before this migration were created ACTIVE under the previous rules: record that origin.
INSERT INTO account_status_history (account_id, previous_status, new_status, reason, actor_subject, actor_role, occurred_at)
SELECT id, NULL, status, 'PRE_APPROVAL_MODEL_ACCOUNT', 'system-migration', 'SYSTEM', created_at FROM accounts;
