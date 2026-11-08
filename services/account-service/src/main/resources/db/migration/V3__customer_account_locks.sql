-- One row per customer, used only as a lock: account creation takes SELECT ... FOR UPDATE on the customer's row
-- so the "open accounts < limit" check and the insert are atomic per customer (InnoDB REPEATABLE READ would
-- otherwise let two concurrent creations both count N-1 and both insert). Rows are created on demand and never
-- deleted; there is deliberately no link to another service's data.
CREATE TABLE customer_account_locks (
    customer_id BINARY(16)  NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_customer_account_locks PRIMARY KEY (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
