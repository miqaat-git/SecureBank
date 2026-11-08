-- Idempotency keys are chosen by clients, so they must be unique per initiating customer, not globally.
-- A global constraint lets one customer squat another's key and probe which keys exist (409 vs 201).
-- Add the new unique key first so a failure part-way never leaves the table without uniqueness.
ALTER TABLE transfer ADD CONSTRAINT uk_transfer_customer_idempotency_key UNIQUE (initiator_customer_id, idempotency_key);
ALTER TABLE transfer DROP CONSTRAINT uk_transfer_idempotency_key;
CREATE INDEX idx_transfer_idempotency_key ON transfer (idempotency_key);
