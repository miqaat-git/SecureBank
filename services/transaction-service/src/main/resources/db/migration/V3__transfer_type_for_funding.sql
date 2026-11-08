-- Funding reuses the transfer saga. The type tells the workflow which ownership rule applies:
-- TRANSFER (customer to customer, source owned by the initiator) or FUNDING (source = the configured clearing
-- account, destination owned by the initiator). Existing rows are ordinary transfers.
ALTER TABLE transfer ADD COLUMN transfer_type VARCHAR(16) NOT NULL DEFAULT 'TRANSFER';
ALTER TABLE transfer ADD CONSTRAINT ck_transfer_type CHECK (transfer_type IN ('TRANSFER', 'FUNDING'));
