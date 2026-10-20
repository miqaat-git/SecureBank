-- Run once as a MySQL administrator. Replace the placeholder passwords OUTSIDE version control.
CREATE DATABASE IF NOT EXISTS securebank_transaction CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Migration account: DDL rights on this database only. Used by Flyway at startup.
CREATE USER IF NOT EXISTS 'tx_migrator'@'%' IDENTIFIED BY '<CHOOSE-A-STRONG-PASSWORD>';
GRANT CREATE, ALTER, INDEX, REFERENCES, DROP, SELECT, INSERT, UPDATE, DELETE
    ON securebank_transaction.* TO 'tx_migrator'@'%';   -- DROP/DELETE are needed by Flyway housekeeping only

-- Application account: DML only. No DDL, no DELETE (the service never deletes financial records).
CREATE USER IF NOT EXISTS 'tx_app'@'%' IDENTIFIED BY '<CHOOSE-ANOTHER-STRONG-PASSWORD>';
GRANT SELECT, INSERT, UPDATE ON securebank_transaction.* TO 'tx_app'@'%';
FLUSH PRIVILEGES;
