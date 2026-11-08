-- =====================================================================
-- SecureBank Account Service - MySQL 8.0 one-time setup (run as a MySQL admin, e.g. root)
-- Replace the two CHANGE_ME_* passwords with long random values BEFORE running.
-- Never commit the edited copy. This script never drops or resets anything.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS securebank_account
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Accounts are created for BOTH loopback host names. On Windows "localhost" can resolve to the IPv6 address ::1,
-- and MySQL matches 'user'@'localhost' and 'user'@'127.0.0.1' as different accounts, so a login that works for one
-- can be "Access denied" for the other. Both accounts get identical privileges and are reachable from this machine only.
-- Use jdbc:mysql://127.0.0.1:3306/... in ACCOUNT_DB_URL (scripts\set-env.example.ps1).
--
-- CREATE USER IF NOT EXISTS never changes the password of an existing user. If a login is refused because the stored
-- password differs from the environment variable, reset only that user (see the commented ALTER USER lines below).

-- Migration account: owns the schema (used only by Flyway at startup / deployment).
CREATE USER IF NOT EXISTS 'account_migrator'@'localhost'  IDENTIFIED BY 'CHANGE_ME_MIGRATOR_PASSWORD';
CREATE USER IF NOT EXISTS 'account_migrator'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME_MIGRATOR_PASSWORD';
GRANT CREATE, ALTER, DROP, INDEX, REFERENCES, SELECT, INSERT, UPDATE, DELETE
    ON securebank_account.* TO 'account_migrator'@'localhost';
GRANT CREATE, ALTER, DROP, INDEX, REFERENCES, SELECT, INSERT, UPDATE, DELETE
    ON securebank_account.* TO 'account_migrator'@'127.0.0.1';
-- (DROP/DELETE are needed by Flyway for its own history table handling and future migrations.)

-- Application account: DML only. No DDL, and NO DELETE (accounts and operations are never deleted).
-- UPDATE is also what allows the per-customer lock row read (SELECT ... FOR UPDATE, migration V3).
CREATE USER IF NOT EXISTS 'account_app'@'localhost'  IDENTIFIED BY 'CHANGE_ME_APP_PASSWORD';
CREATE USER IF NOT EXISTS 'account_app'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME_APP_PASSWORD';
GRANT SELECT, INSERT, UPDATE ON securebank_account.* TO 'account_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON securebank_account.* TO 'account_app'@'127.0.0.1';

FLUSH PRIVILEGES;

-- Password drift only (uncomment, run as administrator, use the SAME value as the environment variable):
-- ALTER USER 'account_migrator'@'localhost'  IDENTIFIED BY '<new migrator password>';
-- ALTER USER 'account_migrator'@'127.0.0.1' IDENTIFIED BY '<new migrator password>';

-- Verify:
-- SELECT user, host FROM mysql.user WHERE user IN ('account_app','account_migrator');
-- SHOW GRANTS FOR 'account_app'@'localhost';
-- SHOW GRANTS FOR 'account_app'@'127.0.0.1';
-- SHOW GRANTS FOR 'account_migrator'@'localhost';
-- SHOW GRANTS FOR 'account_migrator'@'127.0.0.1';
