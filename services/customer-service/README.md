# SecureBank Customer Service

Educational banking simulator. **Fictional customers and simulated data only.** This service stores no
passwords, card data or balances.

> **Status (honest):** this code was written without being compiled or run (the authoring environment had no
> Maven, no network and no JDK compiler). Run `.\mvnw.cmd clean test` first (section 6) and fix anything it reports.
> The MySQL migration V2 has never been executed against a real MySQL server. Back up first (section 12).

## 1. What it does
REST API (port 8081) to create, read, list, search, update customers and read or change their status.
Spring Boot 3.5.16 / Java 21 / MySQL 8 / Flyway / JWT resource server (Keycloak-ready).
Spring Boot 3.5 is the last 3.x line and **no longer receives open-source patches (ended 2026-06-30)**; plan a move to 4.x later.

## 2. Architecture
`Controller -> Service -> Repository -> MySQL`. DTO records for API, JPA entity for persistence, explicit mapper,
central `GlobalExceptionHandler`, constructor injection. Controllers never touch repositories; entities are never returned.

```
src/main/java/com/securebank/customer/
  CustomerServiceApplication.java
  config/OpenApiConfig.java
  controller/CustomerController.java
  dto/        request/response records, PageResponse, ErrorResponse
  entity/Customer.java
  exception/  domain exceptions + GlobalExceptionHandler
  repository/CustomerRepository.java
  security/   SecurityConfig, JWT decoder, Keycloak role converter, 401/403 handlers
  service/    CustomerService, CustomerMapper
src/main/resources/  application.properties, application-local.properties, db/migration/V1, V2
src/test/            unit, repository, web-slice, integration tests + test-only application.properties
```

## 3. Prerequisites
JDK 21, MySQL 8.0 with database `securebank_customer` and the accounts `securebank_customer_migrate` / `securebank_customer_app`.
Maven is optional: `mvnw.cmd` (wrapper) downloads it (needs internet the first time).

## 4. JAVA_HOME (PowerShell)
```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"     # adjust to your JDK folder
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```
If you prefer an installed Maven, use `mvn` instead of `.\mvnw.cmd` everywhere below.

## 5. Build
```powershell
cd path\to\customer-service
.\mvnw.cmd clean package -DskipTests
```

## 6. Run the full test suite
```powershell
.\mvnw.cmd clean test
```
Tests use an in-memory H2 database (new, uniquely named per test context). They never contact MySQL, never need
credentials and cannot touch `securebank_customer`. Security stays on; tests authenticate with Spring Security Test's `jwt()`.
The Flyway scripts are MySQL-specific and are **not** executed by tests; `CustomerMappingTest` checks them textually against the entity.

## 7. Environment variables (no secrets in Git)
| Variable | Meaning |
|---|---|
| `CUSTOMER_DB_URL` | optional, default `jdbc:mysql://localhost:3306/securebank_customer` |
| `CUSTOMER_DB_USERNAME` / `CUSTOMER_DB_PASSWORD` | application account (`securebank_customer_app`) |
| `CUSTOMER_DB_MIGRATION_USERNAME` / `CUSTOMER_DB_MIGRATION_PASSWORD` | Flyway account (`securebank_customer_migrate`) |
| `CUSTOMER_JWT_ISSUER_URI` | optional until Keycloak exists (see section 11) |

```powershell
$env:CUSTOMER_DB_USERNAME = "securebank_customer_app"
$p = Read-Host "App DB password" -AsSecureString
$env:CUSTOMER_DB_PASSWORD = [System.Net.NetworkCredential]::new("", $p).Password
$env:CUSTOMER_DB_MIGRATION_USERNAME = "securebank_customer_migrate"
$p = Read-Host "Migration DB password" -AsSecureString
$env:CUSTOMER_DB_MIGRATION_PASSWORD = [System.Net.NetworkCredential]::new("", $p).Password
```
These last only for the current PowerShell window. `.env` files are git-ignored.

## 8. Start
```powershell
.\mvnw.cmd spring-boot:run
# optional: API docs/Swagger UI (no auth relaxation for customer endpoints)
$env:SPRING_PROFILES_ACTIVE = "local"; .\mvnw.cmd spring-boot:run
```
On start Flyway runs with the migration account, then Hibernate **validates** the schema (`ddl-auto=validate`).

## 9. Verify health
```powershell
Invoke-RestMethod http://localhost:8081/actuator/health              # {"status":"UP"}
Invoke-RestMethod http://localhost:8081/actuator/health/readiness
Invoke-RestMethod http://localhost:8081/actuator/health/liveness
```
Only `health` is exposed, without details.

## 10. API
Base path `/api/v1/customers`. All require a bearer token (section 11). Errors share one shape:
`{"timestamp","status","error","message","path","fieldErrors":[{"field","message"}]}`.

| Method & path | Role | Success |
|---|---|---|
| `POST /` | STAFF or ADMIN | 201 + `Location` |
| `GET /{id}` | STAFF or ADMIN | 200 |
| `GET /?page=0&size=20&sortBy=createdAt&direction=desc` | STAFF or ADMIN | 200 page |
| `GET /search?q=asha&page=0&size=20&sortBy=lastName&direction=asc` | STAFF or ADMIN | 200 page |
| `PUT /{id}` (firstName, lastName, email, phone) | STAFF or ADMIN | 200 |
| `GET /{id}/status` | STAFF or ADMIN | 200 |
| `PATCH /{id}/status` body `{"status":"ACTIVE"\|"SUSPENDED"}` | **ADMIN only** | 200 |

Sortable fields: `firstName,lastName,email,status,createdAt,updatedAt`. Size max 100. Search is a case-insensitive
"contains" over first name, last name, "first last" and email (max 100 chars; `%` and `_` are literal).

**Validation:** names required, max 100, letters/space/`.`/`'`/`-`; email required, max 255, valid, stored trimmed lower-case and unique
(case-insensitive); phone optional, `+`? then 7-15 digits. Unknown JSON fields are rejected (400). Status cannot be set at creation.

Create:
```powershell
$h = @{ Authorization = "Bearer $token"; "Content-Type" = "application/json" }
Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/v1/customers -Headers $h `
  -Body '{"firstName":"Asha","lastName":"Verma","email":"asha@example.test","phone":"+919000000001"}'
```
```json
{"id":"3f2b...","customerNumber":"CUS004821937465","firstName":"Asha","lastName":"Verma",
 "email":"asha@example.test","phone":"+919000000001","status":"ACTIVE",
 "createdAt":"2026-10-19T10:15:30","updatedAt":"2026-10-19T10:15:30"}
```
Status rules: statuses are ACTIVE, SUSPENDED, CLOSED. CLOSED can never be set or left through the API; a closed customer
cannot be updated or reactivated (409). Re-sending the current status is a no-op. Duplicate email = 409, missing customer = 404, bad input = 400.

## 11. JWT / Keycloak
- Set `CUSTOMER_JWT_ISSUER_URI` to your realm URL once Keycloak exists. Signature and issuer are verified through that provider's
  published metadata, fetched on the first token. **No issuer is configured and no Keycloak integration is verified.**
- With no issuer the service starts but rejects every token (fail closed); health still works.
- Expected token: Keycloak realm roles in `realm_access.roles`. Realm role `securebank-staff` -> `ROLE_STAFF`,
  `securebank-admin` -> `ROLE_ADMIN` (names configurable via `securebank.security.jwt.staff-role` / `admin-role`). Other roles are ignored.
- Staff/admin may access every customer; there is no per-customer ownership check (known limitation).
- Local manual testing needs a real token from an identity provider. Automated tests do not need one.

## 12. MySQL permissions and the V1/V2 reconciliation
**Existing state:** V1 (BIGINT `id`, statuses ACTIVE/INACTIVE/BLOCKED, `customer_number`) is already applied. It is unchanged
(a test guards its checksum). The requested UUID/status model is delivered as **V2**, which: adds `customer_id` VARCHAR(36)
(UUID for every existing row) and makes it the primary key, drops `id`, maps INACTIVE/BLOCKED to SUSPENDED, lower-cases emails,
keeps `customer_number`, adds indexes. No rows are deleted. **Date of birth was deliberately not added** (not needed).
Back up first:
```powershell
mysqldump -u <admin_user> -p securebank_customer > securebank_customer_backup.sql
```
Least-privilege grants (replace host as needed; run as an admin account; no passwords here):
```sql
-- application account: DML only, no DDL, no DELETE (nothing deletes customers)
GRANT SELECT, INSERT, UPDATE ON securebank_customer.customers TO 'securebank_customer_app'@'localhost';
-- migration account: schema changes for Flyway (DELETE is only needed by flyway repair)
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
  ON securebank_customer.* TO 'securebank_customer_migrate'@'localhost';
```
UPDATE is required because profile edits and status changes modify rows. If the app account has extra rights:
```sql
REVOKE DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON securebank_customer.* FROM 'securebank_customer_app'@'localhost';
```
(a REVOKE of a privilege not held returns an error; check first.) Verify:
```sql
SHOW GRANTS FOR 'securebank_customer_app'@'localhost';
SHOW GRANTS FOR 'securebank_customer_migrate'@'localhost';
SELECT installed_rank, version, description, success FROM securebank_customer.flyway_schema_history;
SHOW CREATE TABLE securebank_customer.customers;
```
Keep the two accounts and passwords separate. Flyway cannot roll back MySQL DDL: if V2 fails midway, restore the backup.

## 13. Common errors
| Symptom | Fix |
|---|---|
| `Could not resolve placeholder 'CUSTOMER_DB_USERNAME'` | set the env vars in the same PowerShell window (section 7) |
| `Access denied for user` | wrong password/host; check `SHOW GRANTS` |
| `Schema-validation: wrong column type` | V2 not applied, or the schema was changed by hand |
| Flyway `Validate failed: checksum mismatch` | V1/V2 was edited after being applied; restore the original file |
| `Unsupported class file major version` / `release 21` | `JAVA_HOME` is not JDK 21 |
| Every request is 401 | no issuer configured, or token not from the configured issuer |
| 403 | token lacks `securebank-staff`/`securebank-admin` realm role |
| Port 8081 busy | stop the other process or set `SERVER_PORT` |

## 14. VS Code / NetBeans
VS Code: install "Extension Pack for Java" and "Spring Boot Extension Pack", open the `customer-service` folder, set the env vars in
a `launch.json` `env` block or start VS Code from the PowerShell window that holds them (do not commit passwords). NetBeans: open as a
Maven project, set JDK 21, add the env vars under Run configuration, run `CustomerServiceApplication`.

## 15. Integration plan
- **API Gateway:** validates/forwards the Keycloak JWT; this service re-validates it.
- **Account Service:** will reference `customer id` (UUID) and call `GET /{id}/status` to refuse operations for non-ACTIVE customers (service-to-service tokens needed).
- **Transaction / Ledger services:** same status check via Account Service; this service holds no balances.
Not implemented yet.

## 16. Features and known limitations
Implemented: all 11 requested capabilities (create, get, list+sort, update, search, status read, admin status change, validation,
uniform errors, actuator health/readiness, springdoc docs off-by-default).
Limitations: not compiled or run by the author; V2 untested on MySQL; no audit log or status reason; no per-customer ownership;
no date of birth; no CLOSE operation via API; search is "contains" (no index use); timestamps are DB-local `LocalDateTime`
(no timezone); Boot 3.5 is out of OSS support; no rate limiting.
