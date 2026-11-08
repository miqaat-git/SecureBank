-- V2: evolve the already-applied V1 schema (never edit V1; Flyway checksums it).
--   * primary key BIGINT id  ->  UUID text customer_id (VARCHAR(36)); existing rows get a new UUID
--   * status values ACTIVE/INACTIVE/BLOCKED  ->  ACTIVE/SUSPENDED/CLOSED
--     (existing INACTIVE and BLOCKED rows become SUSPENDED; no rows are deleted)
--   * emails normalised to trimmed lower case
--   * search/sort indexes added
-- customer_number is kept (existing data is preserved); the service now generates it.
-- Back up the database before running this against data you care about.

UPDATE customers SET email = LOWER(TRIM(email));

ALTER TABLE customers ADD COLUMN customer_id VARCHAR(36) NULL FIRST;

UPDATE customers SET customer_id = UUID();

ALTER TABLE customers MODIFY COLUMN customer_id VARCHAR(36) NOT NULL;

ALTER TABLE customers DROP PRIMARY KEY, DROP COLUMN id, ADD PRIMARY KEY (customer_id);

ALTER TABLE customers DROP CHECK chk_customers_status;

UPDATE customers SET status = 'SUSPENDED' WHERE status IN ('INACTIVE', 'BLOCKED');

ALTER TABLE customers
    ADD CONSTRAINT chk_customers_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'));

CREATE INDEX idx_customers_last_name_first_name ON customers (last_name, first_name);

CREATE INDEX idx_customers_status ON customers (status);
