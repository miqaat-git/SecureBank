-- SecureBank Transaction Service - initial schema.
-- UUID representation: CHAR(36), canonical lowercase text form (e.g. 3f2b8c1e-...). Chosen for
-- portability between MySQL 8 and the H2 test database and for human-readable operations.
-- The application account needs SELECT, INSERT, UPDATE only (no DELETE, no DDL).

CREATE TABLE transfer (
    id                     CHAR(36)       NOT NULL,
    idempotency_key        VARCHAR(64)    NOT NULL,
    request_fingerprint    VARCHAR(64)    NOT NULL,
    source_account_id      CHAR(36)       NOT NULL,
    destination_account_id CHAR(36)       NOT NULL,
    amount                 DECIMAL(19,2)  NOT NULL,
    currency               VARCHAR(3)     NOT NULL,
    status                 VARCHAR(32)    NOT NULL,
    error_code             VARCHAR(64)    NULL,
    error_message          VARCHAR(255)   NULL,
    initiated_by           VARCHAR(128)   NOT NULL,
    initiator_customer_id  VARCHAR(64)    NOT NULL,
    validation_attempts    INT            NOT NULL,
    next_attempt_at        DATETIME(6)    NULL,
    lock_owner             VARCHAR(64)    NULL,
    lock_expires_at        DATETIME(6)    NULL,
    created_at             DATETIME(6)    NOT NULL,
    updated_at             DATETIME(6)    NOT NULL,
    completed_at           DATETIME(6)    NULL,
    version                BIGINT         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_transfer_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_transfer_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transfer_distinct_accounts CHECK (source_account_id <> destination_account_id)
);

CREATE INDEX idx_transfer_recovery   ON transfer (status, next_attempt_at);
CREATE INDEX idx_transfer_source     ON transfer (source_account_id, created_at);
CREATE INDEX idx_transfer_dest       ON transfer (destination_account_id, created_at);
CREATE INDEX idx_transfer_initiator  ON transfer (initiator_customer_id, created_at);

CREATE TABLE transfer_operation (
    operation_id     CHAR(36)     NOT NULL,
    transfer_id      CHAR(36)     NOT NULL,
    operation_type   VARCHAR(32)  NOT NULL,
    target_service   VARCHAR(32)  NOT NULL,
    idempotency_key  VARCHAR(100) NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    attempt_count    INT          NOT NULL,
    result_code      VARCHAR(64)  NULL,
    last_error       VARCHAR(255) NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    version          BIGINT       NOT NULL,
    PRIMARY KEY (operation_id),
    CONSTRAINT uk_operation_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uk_operation_transfer_type UNIQUE (transfer_id, operation_type),
    CONSTRAINT fk_operation_transfer FOREIGN KEY (transfer_id) REFERENCES transfer (id)
);

CREATE TABLE transfer_history (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    transfer_id      CHAR(36)     NOT NULL,
    previous_status  VARCHAR(32)  NULL,
    new_status       VARCHAR(32)  NOT NULL,
    event_code       VARCHAR(64)  NOT NULL,
    reason           VARCHAR(255) NULL,
    occurred_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_history_transfer FOREIGN KEY (transfer_id) REFERENCES transfer (id)
);

CREATE INDEX idx_history_transfer ON transfer_history (transfer_id, id);
