-- Administrator-only simulated funding (Blueprint D7) and the append-only audit trail.
-- No settlement-provider schema exists in v1. The application account needs SELECT and INSERT on audit_event
-- (rows are never updated or deleted).

ALTER TABLE transfer ADD COLUMN funding_reference VARCHAR(64) NULL;
ALTER TABLE transfer ADD COLUMN funding_reason VARCHAR(255) NULL;
-- customer_id claim carried by the administrator token, if any (used to refuse funding an account they own)
ALTER TABLE transfer ADD COLUMN funding_actor_customer_id VARCHAR(64) NULL;
ALTER TABLE transfer ADD CONSTRAINT uk_transfer_funding_reference UNIQUE (funding_reference);

CREATE TABLE audit_event (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    event_type     VARCHAR(48)  NOT NULL,
    outcome        VARCHAR(16)  NOT NULL,
    actor_subject  VARCHAR(128) NOT NULL,
    actor_role     VARCHAR(16)  NOT NULL,
    transfer_id    CHAR(36)     NULL,
    target_account CHAR(36)     NULL,
    amount         DECIMAL(19,2) NULL,
    currency       VARCHAR(3)   NULL,
    reference      VARCHAR(64)  NULL,
    reason         VARCHAR(255) NULL,
    detail_code    VARCHAR(64)  NULL,
    correlation_id VARCHAR(64)  NULL,
    occurred_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_audit_transfer ON audit_event (transfer_id, occurred_at);
CREATE INDEX idx_audit_actor    ON audit_event (actor_subject, occurred_at);
