-- Phase 0 proof migration. It lives in the TEST resources only and is never part of the
-- application. It proves that the migration account can run Flyway. The real schema starts
-- in Phase 1 under src/main/resources/db/migration.
CREATE TABLE phase0_probe (
    id   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    note VARCHAR(100) NOT NULL
) ENGINE=InnoDB;
