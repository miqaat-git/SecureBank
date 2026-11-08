# SecureBank

SecureBank is an **educational banking simulator**. It has fictional customers and simulated
funds. It is not a real bank, it integrates with no real banking or payment system, and it makes
no regulatory-compliance claim.

This repository is at **Phase 0: Foundation and Compatibility Spike** of the approved blueprint
(v2.3). It contains only the build, the proof that the approved stack works, the profile
skeletons and the database-account and migration mechanism. There is no business domain yet.
Verification status of every Phase 0 check is in `docs/phase0/PHASE0_REPORT.md`.

## Stack

Java 21 · Spring Boot 3.5.16 · Spring MVC · JSP 3.1 with Jakarta JSTL and EL (no scriptlets) ·
Spring Security 6.5 · Spring Data JPA / Hibernate · MySQL 8 · Flyway · Maven · WAR on external
Apache Tomcat 10.1 · HTML, CSS and vanilla JavaScript only · JUnit 5, Mockito, Spring Boot Test,
Spring Security Test, MockMvc, Testcontainers. Exact versions: `docs/phase0/COMPATIBILITY.md`.

## Prerequisites

| Tool | Needed for |
|---|---|
| JDK 21 | everything |
| Maven 3.6.3 or later (3.9.x recommended) | build and tests (the command-line build is the reference build) |
| MySQL 8 | database setup, migrations and the database tests |
| Docker | optional: the database tests start a throwaway `mysql:8.0` container with Testcontainers |
| Apache Tomcat 10.1 (10.1.25 or later) | deploying the WAR to a standalone server |
| NetBeans (a version that supports Jakarta EE 10 and Tomcat 10.1) | optional; see `docs/phase0/NETBEANS_CHECK.md` |

## Configuration

Nothing secret is stored in the repository. The application reads these environment variables:

| Variable | Used by | Meaning |
|---|---|---|
| `SB_DB_URL` | application | JDBC URL of the schema, for example `jdbc:mysql://localhost:3306/securebank` (required unless the dev profile's localhost default applies) |
| `SB_APP_DB_USER`, `SB_APP_DB_PASSWORD` | application | the least-privilege **application** account |
| `SB_MIGRATE_DB_URL`, `SB_MIGRATE_DB_USER`, `SB_MIGRATE_DB_PASSWORD` | `scripts/migrate.sh` only | the **migration** account; the application and the WAR never see these |
| `SB_DB_ADMIN_USER`, `SB_DB_ADMIN_PASSWORD` | `db/setup/create-db-accounts.sh` only | a MySQL administrator, used once to create the schema and accounts |

Profiles (`-Dspring.profiles.active=...`):

- **no profile = production-safe**: Secure session cookie, schema validation only, no migrations.
- `dev`: loopback only, Secure cookie relaxed for plain-HTTP localhost, exposes the Phase 0 proof page.
- `test`: used by the automated tests; also exposes the proof page.
- `prod`: restates the production-safe settings.
- `nodb`: Phase 0 helper that starts the web layer without a database. Combine it with `dev`.

## Build and test

```bash
mvn clean package          # build target/securebank.war and run the tests
mvn test                   # tests only
scripts/verify-war.sh      # inspect the built WAR (after mvn clean package)
```

Database tests (`DatabaseAccountSeparationTest`) use Docker automatically. Without Docker, point
them at a local MySQL 8 development server that you can administer; they create and drop their
own throwaway schema and accounts:

```bash
export SB_TEST_MYSQL_URL=jdbc:mysql://localhost:3306/
export SB_TEST_MYSQL_ADMIN_USER=root
export SB_TEST_MYSQL_ADMIN_PASSWORD='...'
mvn test
```

With neither Docker nor these variables, the database tests are reported as **skipped**.

## Run locally (embedded Tomcat, no database)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev,nodb
curl -i http://localhost:8080/phase0/proof
```

The page lists items produced by JSTL `c:forEach`, an EL computation, and the runtime versions.
The same WAR also runs with `java -jar target/securebank.war --spring.profiles.active=dev,nodb`.

## Database accounts and migrations

See `docs/database-setup.md`. In short: an administrator runs `db/setup/create-db-accounts.sh`
once, and an operator applies migrations with `scripts/migrate.sh`. The application never
migrates.

## Deploy to Tomcat 10.1

See `docs/deploy-tomcat.md`.

## Upgrade path

Boot 3.5 is out of open-source support (3.5.16 was its last open-source release), so this
project must only run on a private or local server with fictional data until it is upgraded.
See `docs/upgrade-path-boot4-tomcat11.md`.
