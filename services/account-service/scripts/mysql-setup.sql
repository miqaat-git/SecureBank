-- =====================================================================
-- SecureBank Account Service - MySQL 8.0 one-time setup (run as a MySQL admin, e.g. root)
-- Replace the two CHANGE_ME_* passwords with long random values BEFORE running.
-- Never commit the edited copy. This script never drops or resets anything.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS securebank_account
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Migration account: owns the schema (used only by Flyway at startup / deployment).
CREATE USER IF NOT EXISTS 'account_migrator'@'localhost' IDENTIFIED BY 'CHANGE_ME_MIGRATOR_PASSWORD';
GRANT CREATE, ALTER, DROP, INDEX, REFERENCES, SELECT, INSERT, UPDATE, DELETE
    ON securebank_account.* TO 'account_migrator'@'localhost';
-- (DROP/DELETE are needed by Flyway for its own history table handling and future migrations.)

-- Application account: DML only. No DDL, and NO DELETE (accounts and operations are never deleted).
CREATE USER IF NOT EXISTS 'account_app'@'localhost' IDENTIFIED BY 'CHANGE_ME_APP_PASSWORD';
GRANT SELECT, INSERT, UPDATE ON securebank_account.* TO 'account_app'@'localhost';

FLUSH PRIVILEGES;

-- Verify:
-- SHOW GRANTS FOR 'account_app'@'localhost';
-- SHOW GRANTS FOR 'account_migrator'@'localhost';
