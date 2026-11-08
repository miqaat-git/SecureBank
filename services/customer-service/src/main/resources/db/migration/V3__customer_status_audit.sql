-- Append-only audit trail of customer status changes. V1 and V2 are untouched.
-- The application account needs only SELECT and INSERT on this table (rows are never updated or deleted).
-- No email, name, phone or credential is stored: only the customer id, the transition, the actor and the reason.

CREATE TABLE customer_status_audit (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id     VARCHAR(36)  NOT NULL,
    previous_status VARCHAR(20)  NOT NULL,
    new_status      VARCHAR(20)  NOT NULL,
    reason          VARCHAR(255) NOT NULL,
    actor_subject   VARCHAR(100) NOT NULL,
    actor_role      VARCHAR(16)  NOT NULL,
    correlation_id  VARCHAR(64)  NULL,
    occurred_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_customer_status_audit PRIMARY KEY (id),
    CONSTRAINT fk_customer_status_audit_customer FOREIGN KEY (customer_id) REFERENCES customers (customer_id),
    CONSTRAINT chk_customer_status_audit_prev CHECK (previous_status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT chk_customer_status_audit_new  CHECK (new_status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT chk_customer_status_audit_diff CHECK (previous_status <> new_status),
    CONSTRAINT chk_customer_status_audit_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_customer_status_audit_customer ON customer_status_audit (customer_id, id);
CREATE INDEX idx_customer_status_audit_actor    ON customer_status_audit (actor_subject, occurred_at);
