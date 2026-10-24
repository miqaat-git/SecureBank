# SecureBank Account Service

Spring Boot microservice of the **SecureBank educational banking simulator** (fictional customers, simulated funds only).
It owns **accounts, account status and account-level balance state**. It is *not* a real banking platform and is
**not certified or ready for real money**.

| | |
|---|---|
| Service / package | `account-service` / `com.securebank.account` |
| Port | `8082` |
| Database | MySQL 8.0, schema `securebank_account` |
| Stack | Java 21, Spring Boot 3.5.x (3.5.6 in `pom.xml`), Maven, Spring Web MVC, Spring Data JPA/Hibernate, Spring Security OAuth2 Resource Server, Bean Validation, Flyway, Actuator, springdoc-openapi 2.8.9 |
| Tests | JUnit 5, Mockito, Spring Boot Test, Spring Security Test, H2 (tests only) |

> ## READ FIRST - verification status
> The environment this service was generated in could not reach Maven Central, so **the project has NOT been compiled
> and the test suite has NOT been executed**. Only static checks were done (all project imports resolve to existing
> files; no syntax errors reported by `javac`'s parser). Run `mvn clean test` (section 5) first; if
> anything fails, send me the output and it will be fixed. See [Known limitations](#12-known-limitations) for the full list.

---

## 1. Prerequisites (Windows 10/11, PowerShell)

1. **JDK 21** (e.g. Temurin). Check: `java -version` must show `21`.
   Install: `winget install EclipseAdoptium.Temurin.21.JDK`, then open a new PowerShell window.
2. **Apache Maven 3.9+**. Check: `mvn -v`. Install: `winget install Apache.Maven` (or download from maven.apache.org and add `bin` to `PATH`).
   *No Maven Wrapper (`mvnw`) is included* - its JAR could not be downloaded when this ZIP was generated. After installing Maven
   you may create one yourself with `mvn wrapper:wrapper`.
3. **MySQL 8.0** server and the `mysql` command-line client (MySQL Installer or `winget install Oracle.MySQL`).
4. A **Customer Service** on `http://localhost:8081` (generated separately) - only needed for account creation.
5. A **Keycloak** realm (future) - only needed to call secured endpoints with real tokens.

## 2. Architecture and folder structure

```
Controller  ->  Service  ->  Repository  ->  MySQL           (controllers never touch repositories or entities)
```

```
account-service/
  pom.xml
  README.md, manifest.txt, .gitignore
  scripts/                         mysql-setup.sql, set-env.example.ps1
  src/main/resources/              application.properties, db/migration/V1__create_account_schema.sql
  src/main/java/com/securebank/account/
    AccountServiceApplication.java
    config/       AccountProperties, ClockConfig, CustomerClientConfig, OpenApiConfig
    security/     SecurityConfig, KeycloakJwtAuthenticationConverter, LazyJwtDecoder,
                  RestSecurityErrorHandlers, CallerContext, CallerResolver
    domain/       Account, BalanceOperation (entities), AccountType, AccountStatus,
                  BalanceOperationType, Money
    repository/   AccountRepository, BalanceOperationRepository, AccountSpecifications
    dto/          request/response records, PageResponse
    mapper/       AccountMapper (explicit entity -> DTO mapping)
    service/      AccountService, BalanceOperationService, AccountNumberGenerator(+Secure impl)
    client/       CustomerClient (port), HttpCustomerClient, CustomerSummary
    exception/    ApiException, ErrorCode, ApiError, GlobalExceptionHandler
    web/          AccountController, AdminAccountController, InternalBalanceController
  src/test/...                     unit, repository, client, migration-contract and MockMvc integration tests
```

### Service ownership

| Concern | Owner |
|---|---|
| Account records, account status, account-level balance state | **Account Service (this)** |
| Customer personal data | Customer Service (never copied here; only a `customerId` UUID is stored) |
| Immutable accounting entries | Ledger Service |
| Transfers / financial workflows, idempotency of business transactions | Transaction Service |
| Routing, TLS, edge security | API Gateway |
| Identity & tokens | Keycloak |

### Data model (`accounts`)

| Column | Type | Rules |
|---|---|---|
| `id` | `BINARY(16)` UUID | primary key, generated in the application |
| `customer_id` | `BINARY(16)` UUID | reference to Customer Service; **no cross-database FK** |
| `account_number` | `VARCHAR(20)` | **unique**; 13 digits: 12 random (SecureRandom, first digit 1-9) + Luhn check digit; reveals nothing about the UUID |
| `account_type` | `VARCHAR(16)` | `SAVINGS` or `CURRENT` (CHECK constraint) |
| `status` | `VARCHAR(16)` | `ACTIVE`, `FROZEN`, `CLOSED` (CHECK) |
| `currency` | `VARCHAR(3)` | `INR` only |
| `available_balance` | `DECIMAL(19,2)` | `BigDecimal`, scale 2, **CHECK >= 0**; closed accounts must be 0 |
| `status_reason/changed_at/changed_by`, `closed_at` | | audit of the last status change |
| `created_at`, `updated_at` | `DATETIME(6)` UTC | |
| `version` | `BIGINT` | optimistic locking (`@Version`) |

`balance_operations` stores one row per applied internal debit/credit (unique `idempotency_key`, FK to `accounts`).
It is **not a ledger**; it only makes retries safe.

UUIDs are stored as `BINARY(16)`. To read them in MySQL: `SELECT BIN_TO_UUID(id), account_number, status, available_balance FROM accounts;`

### Business and financial rules (all enforced in the domain object `Account`)

* Money is always `BigDecimal` with scale 2. **No rounding is ever performed**: an amount with more than 2 decimals is rejected (`INVALID_AMOUNT`).
* Accounts open `ACTIVE` with balance `0.00`. No opening deposit exists; create requests with a `balance` field are rejected (unknown JSON properties -> 400).
* Balance can never be negative (no overdraft) or above 99,999,999,999,999,999.99.
* Only `ACTIVE` accounts accept debits/credits. `FROZEN` and `CLOSED` accounts reject them (`ACCOUNT_NOT_OPERATIONAL`).
* Status machine: `ACTIVE <-> FROZEN`; `ACTIVE -> CLOSED` (terminal).
* **Closure conditions**: status is `ACTIVE` (a frozen account must be unfrozen first) **and** balance is exactly `0.00`. Closing can race with a concurrent credit only safely: one of the two writers fails on the version check.
* Max 5 non-closed accounts per customer (`account.limits.max-open-accounts-per-customer`).
* There is **no public endpoint that writes a balance** (PUT/PATCH/DELETE return 405). Balances change only through the internal idempotent endpoint.

## 3. Environment variables

| Variable | Purpose |
|---|---|
| `ACCOUNT_DB_URL` | JDBC URL, e.g. `jdbc:mysql://localhost:3306/securebank_account?sslMode=PREFERRED&serverTimezone=UTC` |
| `ACCOUNT_DB_USERNAME` / `ACCOUNT_DB_PASSWORD` | runtime application user (DML only) |
| `ACCOUNT_DB_MIGRATION_USERNAME` / `ACCOUNT_DB_MIGRATION_PASSWORD` | Flyway user (DDL) |
| `CUSTOMER_SERVICE_BASE_URL` | default `http://localhost:8081` |
| `ACCOUNT_JWT_ISSUER_URI` | **required**: Keycloak realm issuer URL (no default on purpose) |
| `ACCOUNT_JWT_AUDIENCE` | optional: required `aud` value |

Set them for the current PowerShell window (do not commit real values):

```powershell
cd account-service
Copy-Item scripts\set-env.example.ps1 scripts\set-env.local.ps1   # git-ignored
notepad scripts\set-env.local.ps1                                  # fill in your values
. .\scripts\set-env.local.ps1                                      # note the leading dot
```

## 4. MySQL database and least-privilege accounts

1. Open `scripts\mysql-setup.sql`, replace both `CHANGE_ME_*` passwords (keep an edited copy outside Git).
2. Run it as an administrator:
   ```powershell
   mysql -u root -p < scripts\mysql-setup.sql
   ```
3. Result: database `securebank_account`; `account_migrator` (DDL, used only by Flyway) and `account_app`
   (`SELECT, INSERT, UPDATE` only - **no DDL and no DELETE**). Verify with `SHOW GRANTS FOR 'account_app'@'localhost';`.

Flyway creates the tables (`V1__create_account_schema.sql`) on first start; Hibernate runs with `ddl-auto=validate` and never changes the schema.
The service never touches the Customer Service database. Existing migrations must never be edited after they were applied: add a new `V2__...sql` instead
(if an environment drifted, document and reconcile it with a new migration rather than rewriting history).

## 5. Build and test

```powershell
java -version
mvn -v
mvn clean test          # runs every test (H2 in memory; no MySQL, no Keycloak, no Customer Service needed)
mvn clean package       # builds target\account-service-1.0.0-SNAPSHOT.jar (runs the tests too)
```

Tests are discovered by Maven's default surefire patterns (`*Test.java`). They use the `test` profile
(`src/test/resources/application-test.properties`), an isolated in-memory H2 database, a mocked `CustomerClient`,
and mock JWTs. They never use the production database and nothing is skipped.

Coverage: entity behaviour and money precision, account creation/validation, unique account numbers (pre-check, retry, DB constraint),
Customer Service success/failure (mock HTTP server), retrieval by id/number, listing + pagination, status changes and authorization,
closure rules, invalid balance updates, optimistic-lock and idempotency concurrency, error format/status codes,
security (anonymous / customer / admin / transaction-service), Keycloak role mapping, Flyway-vs-entity contract, application startup.

## 6. Run the service

```powershell
. .\scripts\set-env.local.ps1
mvn spring-boot:run
# or
java -jar target\account-service-1.0.0-SNAPSHOT.jar
```
The service listens on `http://localhost:8082`. If `ACCOUNT_JWT_ISSUER_URI` is missing the application refuses to start with an unresolved-placeholder error.
The issuer is contacted lazily on the first token, so the service starts (and `/actuator/health` works) before Keycloak is reachable.

## 7. Verify health

```powershell
Invoke-RestMethod http://localhost:8082/actuator/health
# status : UP
```
OpenAPI JSON: `http://localhost:8082/v3/api-docs`, Swagger UI: `http://localhost:8082/swagger-ui.html` (both public; click *Authorize* and paste a bearer token to try secured calls).
All other actuator endpoints are disabled.

## 8. API and sample requests

Base path `/api/v1`. Role in brackets: **A** = ADMIN, **C** = CUSTOMER (own accounts only), **T** = TRANSACTION_SERVICE.

| # | Method & path | Roles | Result |
|---|---|---|---|
| 1 | `POST /api/v1/accounts` | A, C(self) | 201 + `Location` |
| 2 | `GET /api/v1/accounts/{accountId}` | A, C(owner) | 200 / 404 |
| 3 | `GET /api/v1/account-numbers/{accountNumber}` | A, C(owner) | 200 / 404 |
| 4 | `GET /api/v1/accounts?customerId=&status=&page=&size=` | A, C(self) | 200 page |
| 5 | `GET /api/v1/admin/accounts?customerId=&status=&accountType=&page=&size=` | A | 200 page |
| 6 | `GET /api/v1/accounts/{accountId}/status` | A, C(owner) | 200 |
| 7 | `POST /api/v1/admin/accounts/{accountId}/freeze` and `/unfreeze` (body `{"reason": "..."}`) | A | 200 / 409 |
| 8 | `POST /api/v1/accounts/{accountId}/close` | A, C(owner) | 200 / 409 |
| 9 | `GET /api/v1/accounts/{accountId}/balance` | A, C(owner) | 200 |
| 10 | `GET /actuator/health` | public | 200 |
| - | `POST /internal/v1/accounts/{accountId}/balance-operations` | T only | 201 applied / 200 replay |

Pagination: `page` >= 0, `size` 1-100 (default 20), newest first. Responses use `{content, page, size, totalElements, totalPages}`.
A customer asking for someone else's account gets **404** (existence is not revealed); listing someone else's accounts gives **403**.

```powershell
$token = "<access token from Keycloak>"          # see section 9
$h = @{ Authorization = "Bearer $token" }

# 1. Create an account (customer must exist and be ACTIVE in Customer Service)
$body = '{"customerId":"3f1c2a52-7d0e-4c0b-9a55-0b7d7a1d9c10","accountType":"SAVINGS"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8082/api/v1/accounts -Headers $h -ContentType "application/json" -Body $body
```
```json
{ "id": "b1c6...", "customerId": "3f1c2a52-...", "accountNumber": "4829175036214", "accountType": "SAVINGS",
  "status": "ACTIVE", "currency": "INR", "availableBalance": 0.00,
  "createdAt": "2026-10-19T09:00:00Z", "updatedAt": "2026-10-19T09:00:00Z", "version": 0 }
```
```powershell
Invoke-RestMethod -Uri "http://localhost:8082/api/v1/accounts?customerId=3f1c2a52-7d0e-4c0b-9a55-0b7d7a1d9c10&size=10" -Headers $h
Invoke-RestMethod -Uri http://localhost:8082/api/v1/accounts/<accountId>/balance -Headers $h
Invoke-RestMethod -Method Post -Uri http://localhost:8082/api/v1/admin/accounts/<accountId>/freeze -Headers $h -ContentType "application/json" -Body '{"reason":"Fraud review"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8082/api/v1/accounts/<accountId>/close -Headers $h
```

Internal call from the Transaction Service (service-account token with role `transaction-service`):
```powershell
$body = '{"operationType":"CREDIT","amount":250.00,"idempotencyKey":"txn-8841-leg-2","referenceId":"0b8a4a6e-1d3c-4f5e-8c0a-2f5f7f3d9b11"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8082/internal/v1/accounts/<accountId>/balance-operations -Headers $h -ContentType "application/json" -Body $body
```
Sending the **same** key and payload again returns `200` with `"replayed": true` and changes nothing; the same key with a different payload returns `409 IDEMPOTENCY_KEY_REUSED`.

### Error format (every error)
```json
{ "timestamp": "2026-10-19T09:00:00Z", "status": 409, "error": "Conflict", "code": "ACCOUNT_CLOSURE_NOT_ALLOWED",
  "message": "The account balance must be zero before the account can be closed.", "path": "/api/v1/accounts/b1c6.../close" }
```
Validation errors add `"fieldErrors": [{"field": "accountType", "message": "..."}]`. Stack traces, SQL and framework messages are never returned (they are logged server-side).

| HTTP | `code` examples |
|---|---|
| 400 | `VALIDATION_FAILED`, `UNSUPPORTED_CURRENCY`, `INVALID_AMOUNT`, `BAD_REQUEST` |
| 401 / 403 | `AUTHENTICATION_REQUIRED` / `ACCESS_DENIED` |
| 404 | `ACCOUNT_NOT_FOUND` |
| 409 | `INVALID_ACCOUNT_STATE`, `ACCOUNT_NOT_OPERATIONAL`, `ACCOUNT_CLOSURE_NOT_ALLOWED`, `ACCOUNT_LIMIT_EXCEEDED`, `IDEMPOTENCY_KEY_REUSED`, `CONCURRENT_MODIFICATION` |
| 422 | `CUSTOMER_NOT_FOUND`, `CUSTOMER_NOT_ELIGIBLE`, `INSUFFICIENT_FUNDS`, `BALANCE_LIMIT_EXCEEDED` |
| 503 | `CUSTOMER_SERVICE_UNAVAILABLE`, `ACCOUNT_NUMBER_UNAVAILABLE` |

## 9. JWT security and authorization

* Stateless OAuth2 **resource server**; signature (JWKS discovered from the issuer), expiry, **issuer** and optional **audience** are validated.
  CSRF is disabled because there are no cookies/sessions, only bearer tokens.
* Public: `/actuator/health/**`, `/v3/api-docs/**`, `/swagger-ui/**`. Everything not listed is denied.

| URL | Required authority |
|---|---|
| `/internal/**` | `ROLE_TRANSACTION_SERVICE` |
| `/api/v1/admin/**` | `ROLE_ADMIN` |
| `/api/v1/**` | `ROLE_ADMIN` or `ROLE_CUSTOMER`; ownership is then enforced in the service layer |

### Keycloak role -> authority mapping
| Keycloak | Spring authority |
|---|---|
| realm role `admin` (`realm_access.roles`) | `ROLE_ADMIN` |
| realm role `customer` | `ROLE_CUSTOMER` |
| realm role `transaction-service` | `ROLE_TRANSACTION_SERVICE` |
| client role in `resource_access.securebank-account-service.roles` | same rule (`some-role` -> `ROLE_SOME_ROLE`) |

Roles of other clients and OAuth scopes are ignored. A CUSTOMER token must carry a **`customer_id` claim** (UUID; add a Keycloak user-attribute mapper)
- that is how ownership is decided. The claim name is configurable (`account.security.customer-id-claim`).

### Service-to-service strategy (internal operations)
The Transaction Service authenticates with the OAuth2 **client-credentials** grant using its own Keycloak client; its service account
has only the realm role `transaction-service`. Customer/admin tokens get 403 on `/internal/**`. Recommended extras: set `ACCOUNT_JWT_AUDIENCE`
so tokens must be issued for this service, **do not route `/internal/**` through the API Gateway**, and restrict the port to the internal network (mTLS later).

### Tests vs. production security
Tests use the **same filter chain** as production. They authenticate through spring-security-test's `jwt()` mock tokens and a stub `JwtDecoder`
that rejects every raw token (active only in the `test` profile). Production security is never disabled.

## 10. Customer Service integration

On account creation the service calls (before opening any DB transaction):

```
GET {CUSTOMER_SERVICE_BASE_URL}/api/v1/customers/{customerId}       (path configurable: account.customer-service.customer-path)
200  {"id": "<same uuid>", "status": "ACTIVE", ...}    extra fields are ignored and never stored
404  customer does not exist
```
* Eligible if `status` is in `account.customer-service.eligible-statuses` (default `ACTIVE`). Missing -> `422 CUSTOMER_NOT_FOUND`; ineligible -> `422 CUSTOMER_NOT_ELIGIBLE`.
* **Any** other outcome (timeout, connection error, 5xx, 401/403, empty body, mismatching id) -> `503 CUSTOMER_SERVICE_UNAVAILABLE`. A customer is never assumed to exist.
* Timeouts: connect 2s, read 3s (configurable). The caller's bearer token is forwarded (`account.customer-service.forward-caller-token`).
* **This contract is an assumption.** It has not been tested against a real Customer Service because none was available; adjust URL/path/field names in configuration or in `HttpCustomerClient` once that service exists.
* The `CustomerClient` interface lets tests mock the remote service.

## 11. Balance ownership, concurrency and failure scenarios

**Ownership.** `available_balance` is Account Service's own state for ordinary operations. The Ledger Service remains the system of record for accounting entries.
This service never calls other services' databases and does not implement a transfer endpoint.

**Concurrency.** `@Version` optimistic locking: two writers can never silently overwrite each other. Internal operations retry a bounded number of times (5) on conflict, then return
`409 CONCURRENT_MODIFICATION`. Idempotency keys have a unique DB constraint, so parallel identical requests apply once.

**Why there is no transfer endpoint.** A transfer debits one account and credits another in *different* HTTP calls; that cannot be one ACID transaction. Planned coordination (owned by the Transaction Service):

1. Transaction Service stores the transfer (`PENDING`) with a stable id, and derives idempotency keys per leg (e.g. `txn-<id>-debit`, `txn-<id>-credit`).
2. Calls `balance-operations` DEBIT on the source. `422 INSUFFICIENT_FUNDS` / `409 ACCOUNT_NOT_OPERATIONAL` -> transfer fails, nothing to undo.
3. Calls CREDIT on the destination. On a destination failure it **compensates** by a CREDIT back to the source with its own key (`txn-<id>-compensate`), retrying until it succeeds (account may be frozen -> operator action).
4. Asks the Ledger Service to append immutable entries (idempotent on the transaction id) and marks the transfer `COMPLETED`.

| Failure | Effect / recovery |
|---|---|
| Timeout or crash after Account Service committed, response lost | Retry with the same key -> `200 replayed:true`, no double debit |
| Transaction Service crashes between debit and credit | Recovery job resumes from the stored `PENDING` state (retries or compensates) |
| Ledger write fails after both balances changed | Retry ledger (idempotent); reconciliation compares `balance_operations.reference_id` with ledger entries |
| Concurrent balance change | `409 CONCURRENT_MODIFICATION` -> retry with the same key |
| Account frozen/closed mid-transfer | `409 ACCOUNT_NOT_OPERATIONAL` -> fail or compensate |
| Customer Service down | Account creation returns `503`; existing accounts are unaffected |

## 12. Known limitations

* **Not compiled, not executed.** No Maven Central access was available when generating this ZIP, so `mvn test` has never been run. Dependency
  versions (Boot 3.5.6, springdoc 2.8.9) were chosen from knowledge of the 3.5.x line and could not be resolved/verified here; any later 3.5.y Boot patch is a drop-in change.
  Expect that a first run may reveal small compile or test issues.
* **Flyway/MySQL not exercised.** Tests use H2 with Hibernate-generated schema; the MySQL migration is only checked statically against the entity mappings
  (`FlywayMigrationContractTest`). First real check: start the service against an empty MySQL database (Flyway runs, then Hibernate `validate` confirms the schema).
  Known risk areas: `DATETIME(6)` vs `Instant`, `BINARY(16)` UUIDs, enum `VARCHAR` columns (mitigated via `@JdbcTypeCode` and `preferred_instant_jdbc_type=TIMESTAMP`).
* No Maven Wrapper (JAR could not be downloaded); install Maven or run `mvn wrapper:wrapper`.
* The Customer Service contract (section 10) and the Keycloak setup (`customer_id` claim, roles) are assumptions, not tested against real services.
* No holds/reservations, overdraft, interest, fees, account approval workflow, audit-event stream, rate limiting or token revocation checks.
* The account-count limit is not strictly race-free (two simultaneous creations could exceed it by one).
* Closure of a frozen account is intentionally blocked; unfreeze first.
* `status_changed_by` stores the JWT subject (a user id, not personal data), no names or e-mail addresses.
* Educational design: **not production-certified, not ready for real banking.**

## 13. Integration with other services

* **Transaction Service**: sole caller of `/internal/v1/accounts/{id}/balance-operations` (client-credentials token with role `transaction-service`). It must supply a unique idempotency key per leg and the transaction id as `referenceId`.
* **Ledger Service**: no direct coupling. It can reconcile using `balance_operations` (`reference_id`, `amount`, `resulting_balance`) through a future read endpoint or event; Account Service never writes ledger entries.
* **API Gateway**: route `/api/v1/accounts/**`, `/api/v1/account-numbers/**`, `/api/v1/admin/accounts/**` to `http://localhost:8082`; **block `/internal/**`**; forward the `Authorization` header unchanged. Note that `/api/v1/customers/**` belongs to the Customer Service, which is why this service lists accounts with `GET /api/v1/accounts?customerId=`.
* **Keycloak**: set `ACCOUNT_JWT_ISSUER_URI` to the realm issuer; create roles `admin`, `customer`, `transaction-service`; add a `customer_id` claim mapper for customers.

## 14. Troubleshooting

| Symptom | Fix |
|---|---|
| `Could not resolve placeholder 'ACCOUNT_DB_URL'` (or another variable) | Run `. .\scripts\set-env.local.ps1` in the same PowerShell window; check `Get-ChildItem Env:ACCOUNT*` |
| `release version 21 not supported` / `invalid target release` | Maven is using an older JDK: set `JAVA_HOME` to the JDK 21 folder |
| `Access denied for user` | Wrong user/password; re-run `scripts\mysql-setup.sql`; use `account_migrator` only for the migration variables |
| `Public Key Retrieval is not allowed` | Add `&allowPublicKeyRetrieval=true` to `ACCOUNT_DB_URL` for local development only |
| `Schema-validation: wrong column type ...` | Migration and entity differ: add a new `V2__` migration (never edit an applied one) or fix the entity; send me the full message |
| `Flyway ... Validate failed / checksum mismatch` | An applied migration file was edited; restore it and add a new version instead |
| `Port 8082 was already in use` | `netstat -ano \| findstr 8082`, stop the process, or run with `--server.port=8083` |
| 401 on every call | Token missing/expired, wrong issuer (`ACCOUNT_JWT_ISSUER_URI` must equal the token's `iss`), or Keycloak unreachable (see service log) |
| 403 on a valid token | Missing role (`admin`/`customer`/`transaction-service`), or a customer token without `customer_id` |
| 503 `CUSTOMER_SERVICE_UNAVAILABLE` | Customer Service not running on `CUSTOMER_SERVICE_BASE_URL` or its contract differs (section 10) |
| 409 `CONCURRENT_MODIFICATION` | Another change won the race; reload and retry (same idempotency key for internal calls) |
| Tests fail to download dependencies | Check internet/proxy access to Maven Central, then `mvn -U clean test` |

## Canonical internal contract for the Transaction Service (Checkpoint 2, UNVERIFIED: not yet compiled or run)

All routes require `ROLE_TRANSACTION_SERVICE` (`/internal/**`) and must never be routed by the Gateway.

| Method | Path | Result |
|---|---|---|
| GET | `/internal/accounts/{accountId}` | `{accountId, customerId, status, currency}` (no balance) |
| POST | `/internal/accounts/{accountId}/operations` | 201 applied / 200 idempotent replay, body `status: "SUCCEEDED"` |
| GET | `/internal/accounts/{accountId}/operations/{operationId}` | 200 recorded / 404 `OPERATION_NOT_FOUND` |

Request: `{operationId, type: DEBIT|CREDIT|REVERSE_DEBIT, amount, currency, transferId, reversesOperationId?}`.
`operationId` is the idempotency key. `reversesOperationId` is required for `REVERSE_DEBIT` only; the named debit
must exist on the same account with the same amount and transfer, and can be reversed once
(`OPERATION_ALREADY_REVERSED`, enforced by the unique `reverses_key` column added in `V2`).
Errors never return 2xx. A 409 `CONCURRENT_MODIFICATION` means nothing was applied; Transaction Service retries it.
The older `/internal/v1/accounts/{id}/balance-operations` endpoint is kept unchanged (DEBIT/CREDIT only).
Known limitation: reversal credits are refused on FROZEN/CLOSED accounts (the transfer escalates to reconciliation).
Run `V2` against a real MySQL 8.0.16+ before relying on it; the H2 tests do not execute Flyway scripts.
