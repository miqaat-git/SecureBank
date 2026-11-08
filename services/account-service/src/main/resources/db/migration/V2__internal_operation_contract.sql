-- =====================================================================
-- SecureBank Account Service - V2: canonical internal operation contract
-- (MySQL 8.0.16+; DROP CHECK requires 8.0.16 or later)
--
-- Additive: no data is dropped or rewritten.
--  * allows operation_type REVERSE_DEBIT (compensation for a DEBIT)
--  * reverses_key records which debit (by idempotency key) a reversal
--    undoes; it is UNIQUE so one debit can be reversed at most once even
--    when a retry arrives with a different operation id.
-- Must be run against MySQL before relying on it: the H2 test schema is
-- generated from the entities and does not execute this script.
-- =====================================================================

ALTER TABLE balance_operations DROP CHECK ck_balance_operations_type;

ALTER TABLE balance_operations
    ADD CONSTRAINT ck_balance_operations_type
    CHECK (operation_type IN ('DEBIT', 'CREDIT', 'REVERSE_DEBIT'));

ALTER TABLE balance_operations ADD COLUMN reverses_key VARCHAR(64) NULL;

ALTER TABLE balance_operations
    ADD CONSTRAINT uq_balance_operations_reverses UNIQUE (reverses_key);
