# Database accounts and migrations (blueprint 3.1, C7)

Two MySQL accounts exist. The running application never holds the migration account.

| Account | Used by | Privileges |
|---|---|---|
| `securebank_app` (default name) | the application on Tomcat | Phase 0 baseline: `SELECT, INSERT` on the schema. No `UPDATE`, no `DELETE`, no DDL, no `GRANT`. |
| `securebank_migrate` (default name) | Flyway only | `CREATE, ALTER, DROP, INDEX, REFERENCES, CREATE VIEW, TRIGGER, SELECT, INSERT, UPDATE, DELETE` on the SecureBank schema only. |

The grants live in one place, `db/setup/accounts.sql.template`. The shell script and the
automated test both use that file, so what the test proves is what the script grants.

## Why the application baseline is only SELECT and INSERT (open item N3)

The final design gives the application `UPDATE` only on the mutable tables and never on the
append-only tables (blueprint 3.1). Table-level privileges can only follow table creation, so
Phase 0 grants `SELECT, INSERT` on the whole schema, which already satisfies the append-only
rule. Phase 1 proposes an idempotent grants script that the operator re-runs after each
migration set to add table-level `UPDATE` on the mutable tables. Whether MySQL accepts a
table-level grant before the table exists was not tested and is not relied on.

## 1. Create the schema and accounts (once per environment, by an administrator)

```bash
export SB_DB_ADMIN_USER=root
export SB_DB_ADMIN_PASSWORD='<administrator password>'
export SB_APP_DB_PASSWORD='<16+ random characters from A-Za-z0-9._+=->'
export SB_MIGRATE_DB_PASSWORD='<a different 16+ character password>'
db/setup/create-db-accounts.sh
```

The script reads nothing from the repository except the SQL template, passes the administrator
password through `MYSQL_PWD`, sends the account passwords through standard input, and refuses
weak or malformed values. Optional overrides are listed at the top of the script.

## 2. Apply migrations (a separate step, with the migration account)

```bash
export SB_MIGRATE_DB_URL=jdbc:mysql://localhost:3306/securebank
export SB_MIGRATE_DB_USER=securebank_migrate
export SB_MIGRATE_DB_PASSWORD='<migration password>'
scripts/migrate.sh info       # show state
scripts/migrate.sh migrate    # apply
```

The migration location is `src/main/resources/db/migration`. It is empty in Phase 0: Phase 1
adds the first real migration. The only Phase 0 migration, `V0_1__phase0_probe.sql`, lives in the
**test** resources and proves the mechanism in `DatabaseAccountSeparationTest`.

## 3. Run the application

The application uses only `securebank_app` (`SB_APP_DB_USER`, `SB_APP_DB_PASSWORD`). In every
profile `spring.flyway.enabled=false`, so the application cannot migrate, and Hibernate only
validates (`ddl-auto=validate`).

## Keeping migration credentials out of the runtime

- `application*.properties` never contain migration credentials (checked by
  `ProfileConfigurationTest`).
- The Flyway Maven plugin takes its credentials from the environment only and is not bound to
  any lifecycle phase, so `mvn package` never uses them.
- The WAR contains no `pom.xml` and no Flyway jars (checked by `scripts/verify-war.sh`).
- In production, set the migration variables only in the shell that runs `scripts/migrate.sh`,
  not in the Tomcat environment.

## Local MySQL fallback for the tests

When Docker is unavailable, set `SB_TEST_MYSQL_URL`, `SB_TEST_MYSQL_ADMIN_USER` and
`SB_TEST_MYSQL_ADMIN_PASSWORD` (see the README). Use a development server only: the test creates
and then drops a throwaway schema and two accounts.
