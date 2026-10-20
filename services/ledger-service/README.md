# SecureBank Ledger Service

Double-entry accounting ledger for the **SecureBank educational banking simulator**. All customers and funds
are fictional; nothing here moves real money or talks to a real payment system.

- Java 21, Spring Boot 3.5.15, Maven, MySQL 8.0, Flyway, Spring Security OAuth2 Resource Server (Keycloak-ready JWT)
- Package `com.securebank.ledger`, port **8083**, database **`securebank_ledger`**
- Architecture: `Controller -> Service -> Repository -> MySQL` (entities never leave the service layer)

> **Status:** this source was written without being compiled or run (the authoring environment had no access
> to Maven Central). Run `mvn clean verify` yourself first and fix anything it reports. See "Known limitations".

## What the service owns

Ledger Service is the source of truth for **posted accounting entries** only. It does **not** own customer
profiles (Customer Service), account lifecycle or operational/available balances (Account Service),
authentication (Keycloak) or transaction orchestration/retries (Transaction Service).

## 1. Prerequisites (Windows PowerShell)

1. JDK 21 (`java -version` must show 21).
2. Apache Maven 3.9+ (`mvn -v`).
3. MySQL 8.0 running locally (default port 3306).
4. *(Optional, to generate the Maven wrapper)* `mvn -N wrapper:wrapper -Dmaven=3.9.11` - the wrapper files are
   not included in this ZIP because they could not be generated offline.

## 2. Database and least-privilege users

A service cannot connect to a schema that does not exist yet, so the schema and two accounts are created once
by an administrator with `docs/database/01-create-database-and-users.sql`. Edit the two password placeholders
first, then:

```powershell
Get-Content .\docs\database\01-create-database-and-users.sql | mysql -u root -p
```

| Account | Used by | Privileges |
|---|---|---|
| `ledger_migrator` | Flyway only (`LEDGER_DB_MIGRATION_*`) | CREATE, ALTER, INDEX, REFERENCES, SELECT, INSERT, UPDATE, DELETE on `securebank_ledger.*` (UPDATE/DELETE for Flyway's own history table) |
| `ledger_app` | The running service (`LEDGER_DB_*`) | **SELECT, INSERT only** - no DDL, no UPDATE, no DELETE |

Flyway (`V1__create_ledger_tables.sql`) then creates all tables, constraints and indexes on an empty schema.
Hibernate runs with `ddl-auto=validate` and never changes the schema. Never edit an applied migration; add
`V2__...sql` instead. The service never touches other services' databases.

## 3. Environment variables (set values yourself; never commit them)

| Variable | Meaning |
|---|---|
| `LEDGER_DB_URL` | JDBC URL, e.g. `jdbc:mysql://localhost:3306/securebank_ledger?serverTimezone=UTC` |
| `LEDGER_DB_USERNAME` / `LEDGER_DB_PASSWORD` | Runtime account (`ledger_app`) |
| `LEDGER_DB_MIGRATION_USERNAME` / `LEDGER_DB_MIGRATION_PASSWORD` | Flyway account (`ledger_migrator`) |
| `LEDGER_JWT_ISSUER_URI` | Issuer of the Keycloak realm = the `iss` claim of accepted tokens, no trailing `/` (required). Local dev: `http://localhost:8180/realms/securebank` from the `identity` folder of the SecureBank root (see `docs/AUTHENTICATION.md`) |

```powershell
$env:LEDGER_DB_URL = "jdbc:mysql://127.0.0.1:3306/securebank_ledger?serverTimezone=UTC"
$env:LEDGER_DB_USERNAME = "ledger_app"
$env:LEDGER_DB_PASSWORD = Read-Host "ledger_app password"
$env:LEDGER_DB_MIGRATION_USERNAME = "ledger_migrator"
$env:LEDGER_DB_MIGRATION_PASSWORD = Read-Host "ledger_migrator password"
$env:LEDGER_JWT_ISSUER_URI = "http://localhost:8180/realms/securebank"
```

The service refuses to start if these are missing and prints which variable is missing (see
`MissingEnvironmentVariableFailureAnalyzer`); a malformed issuer (empty, not http(s), trailing `/`) also stops
startup (`JwtIssuerStartupValidator`). No Keycloak URL, token or secret is built in. The identity provider
itself is contacted on the first token, so the service can start before Keycloak does, but every request is
rejected with 401 until Keycloak is reachable. MySQL accounts are created for host `127.0.0.1`, so use
`127.0.0.1` (not `localhost`) in `LEDGER_DB_URL`. Template for all services: `scripts\set-env.example.ps1` in
the SecureBank root.

Optional settings (`application.yml`, prefix `ledger.`): `accounting.supported-currencies` (default `INR`),
`accounting.max-amount-scale` (2), `accounting.max-line-amount` (100000000), `accounting.max-lines-per-entry`
(200), `security.public-api-docs` (true), `security.resource-client-id` (empty).

## 4. Build, test, run

```powershell
mvn clean verify          # compiles and runs all tests (H2 in memory; needs no MySQL/Keycloak/credentials)
mvn spring-boot:run       # runs on http://localhost:8083 (needs the environment variables above)
Invoke-RestMethod http://localhost:8083/actuator/health          # {"status":"UP"}
Invoke-RestMethod http://localhost:8083/actuator/health/readiness
```

OpenAPI JSON: `/v3/api-docs`; Swagger UI: `/swagger-ui.html` (public by default, set
`ledger.security.public-api-docs=false` to require a ledger role).

## 5. Accounting convention

- Every line has a `debitAmount` and a `creditAmount`: both non-negative, **exactly one positive**, the other
  zero (or omitted).
- Every entry has **at least two lines**, one currency (initially `INR`), unique line numbers, and
  **total debits == total credits**, compared exactly with `BigDecimal.compareTo` (never floating point).
- Amounts are `DECIMAL(19,4)` in storage; the service accepts at most 2 decimal places and at most
  100,000,000 per line by default. Over-precise amounts are **rejected, never rounded**.
- Posted entries are immutable. A correction is a **reversal**: a new entry that mirrors every line with debit
  and credit swapped and references the original.
- **Net movement of an account** = `sum(debitAmount) - sum(creditAmount)` over its posted lines (debit-positive).
  For a credit-normal account (e.g. a customer deposit liability) the balance-increasing direction is the
  negative of that number. This is an *accounting view*, **not** the account's operational/available balance -
  Account Service owns that.
- References (`businessReference`, `accountReference`) are upper-case ASCII `A-Z 0-9 . _ : -` (3-100 / 3-64 chars)
  so behaviour is identical under MySQL's case-insensitive collation.

## 6. API

All endpoints are under `/api/v1`, send/receive JSON, and require `Authorization: Bearer <JWT>`.

| Method & path | Purpose | Role |
|---|---|---|
| `POST /journal-entries` | Create + post a balanced entry (201, or 200 on idempotent replay) | `LEDGER_WRITER` |
| `GET /journal-entries/{id}` | Entry with lines | reader/writer/reverser |
| `GET /journal-entries/by-reference/{businessReference}` | Entry by unique reference | reader/writer/reverser |
| `GET /journal-entries?status=&reference=&from=&to=&page=&size=` | Paged entry headers, newest first | reader/writer/reverser |
| `GET /journal-entries/{id}/lines` | Lines of one entry | reader/writer/reverser |
| `GET /accounts/{accountReference}/ledger-lines?from=&to=&page=&size=` | Paged lines of an account | reader/writer/reverser |
| `GET /accounts/{accountReference}/movement?currency=&from=&to=` | Net movement (accounting view) | reader/writer/reverser |
| `POST /journal-entries/{id}/reversals` | Compensating reversal (201, or 200 on replay) | `LEDGER_REVERSER` |
| `GET /actuator/health[/liveness\|/readiness]` | Health (public, no details) | none |

Dates are ISO-8601 instants such as `2026-01-31T00:00:00Z` (use `Z`; a `+` offset must be URL-encoded). The range
is `[from, to)`. `size` is 1-200 (default 20).

### Balanced request -> 201

```json
POST /api/v1/journal-entries
{
  "businessReference": "TXN-2026-000123",
  "currency": "INR",
  "description": "Simulated cash deposit",
  "lines": [
    {"lineNumber": 1, "accountReference": "ACC-CASH-001", "debitAmount": 1000.00, "creditAmount": 0},
    {"lineNumber": 2, "accountReference": "ACC-CUST-0001", "debitAmount": 0, "creditAmount": 1000.00}
  ]
}
```

```json
{
  "id": "6f1c1c0e-6a0e-4f0f-9a35-0b8c3a7f2a11",
  "businessReference": "TXN-2026-000123",
  "currency": "INR",
  "description": "Simulated cash deposit",
  "status": "POSTED",
  "entryType": "NORMAL",
  "totalAmount": 1000.0000,
  "reversesEntryId": null,
  "reversedByEntryId": null,
  "createdBy": "svc-transaction-service",
  "createdAt": "2026-01-31T09:15:30.123456Z",
  "lines": [
    {"id": "...", "lineNumber": 1, "accountReference": "ACC-CASH-001", "debitAmount": 1000.0000, "creditAmount": 0.0000, "currency": "INR", "description": null},
    {"id": "...", "lineNumber": 2, "accountReference": "ACC-CUST-0001", "debitAmount": 0.0000, "creditAmount": 1000.0000, "currency": "INR", "description": null}
  ]
}
```

### Unbalanced request -> 422

Same body with the credit changed to `999.99`:

```json
{
  "timestamp": "2026-01-31T09:16:00.000Z",
  "status": 422,
  "code": "UNBALANCED_ENTRY",
  "message": "Total debits (1000.00) must equal total credits (999.99)",
  "path": "/api/v1/journal-entries"
}
```

### Reversal

```json
POST /api/v1/journal-entries/6f1c1c0e-6a0e-4f0f-9a35-0b8c3a7f2a11/reversals
{ "businessReference": "REV-2026-000123", "reason": "Posted in error" }
```

Returns `201` with a new `REVERSAL` entry (`reversesEntryId` = original id, debit/credit swapped on the same
accounts and line numbers). Afterwards `GET /journal-entries/{id}` on the original shows `reversedByEntryId`.

## 7. Idempotency and reversal behaviour

- `businessReference` is unique **in the database** (`uk_journal_entries_business_reference`).
- Same reference + **equivalent** request (same currency, description and lines; amount scale, line order and
  surrounding whitespace ignored; compared via a stored SHA-256 fingerprint) -> original entry returned with
  **200** and header `Idempotent-Replay: true`; nothing new is written.
- Same reference + **different** request -> **409** `BUSINESS_REFERENCE_CONFLICT`.
- Entry and all lines are written in **one transaction**. If two identical requests race, the database unique
  constraint lets exactly one win; the loser's transaction is rolled back and it replays the winner's result.
- A reversal is idempotent per original entry: the same request (same reversal reference and reason) replays the
  existing reversal (200). A *different* second reversal -> **409** `ENTRY_ALREADY_REVERSED` (also enforced by
  the unique `reverses_entry_id` column). A reversal cannot itself be reversed (`REVERSAL_NOT_ALLOWED`).
- Idempotency protects **ledger writes**. It is **not** exactly-once delivery across microservices:
  orchestration, timeouts and delivery retries belong to Transaction Service, which must reuse the same
  `businessReference` when it retries.

## 8. Security

- Stateless OAuth2 Resource Server; Spring Security validates the JWT signature and **issuer**
  (`LEDGER_JWT_ISSUER_URI`). Missing/invalid token -> 401, insufficient role -> 403 (both as the standard JSON error).
- Public: `/actuator/health/**` and (by default) OpenAPI docs only. Everything else is denied unless listed above.
- **Keycloak role mapping** - realm roles in `realm_access.roles` (and, if `ledger.security.resource-client-id` is
  set, `resource_access.<client>.roles`) become authorities by upper-casing and replacing `-` with `_`:

  | Keycloak role | Authority | Grants |
  |---|---|---|
  | `ledger-reader` | `ROLE_LEDGER_READER` | all `GET` endpoints |
  | `ledger-writer` | `ROLE_LEDGER_WRITER` | `POST /journal-entries` (+ reads) |
  | `ledger-reverser` | `ROLE_LEDGER_REVERSER` | `POST .../reversals` (+ reads) |

- **Service-to-service (future Transaction Service):** register it as a confidential Keycloak client using the
  client-credentials grant; give its service account the `ledger-writer` and `ledger-reverser` roles (not
  more). It calls the Ledger with the access token as a bearer token. Humans/back-office tools get
  `ledger-reader`; reversals by people should require `ledger-reverser` and be exposed only through
  Transaction Service. Not implemented/verified here because those components do not exist yet.
- `createdBy` stores only the JWT `sub` (no personal data). No customer personal information is stored.

## 9. Integration contracts (planned, not implemented in this repository)

**Transaction Service (8084) -> Ledger:** for each business event it calls `POST /api/v1/journal-entries` with a
deterministic `businessReference` derived from its own transaction id and retries with the *same* body on
timeouts (200 replay is success). It treats 409 as a bug/conflict to investigate and 422 as a permanent
rejection. Corrections use `POST .../reversals`.

**Account Service (8082):** owns account identity and operational balances. It never writes to the Ledger.
`accountReference` values in ledger lines should be the Account Service's account reference. Ledger movement
can be used to reconcile against Account Service balances but never replaces them.

No integration with Customer/Account/Transaction Service or the API Gateway exists yet.

## 10. Common errors

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_PARAMETER`, `INVALID_QUERY` | Bad shape, unknown field, bad id/date/paging |
| 401 / 403 | `UNAUTHENTICATED` / `ACCESS_DENIED` | No/invalid token / missing role |
| 404 | `ENTRY_NOT_FOUND` | Unknown id or reference |
| 409 | `BUSINESS_REFERENCE_CONFLICT`, `ENTRY_ALREADY_REVERSED`, `REVERSAL_NOT_ALLOWED`, `DATA_INTEGRITY_VIOLATION` | Idempotency/state conflict |
| 422 | `UNBALANCED_ENTRY`, `TOO_FEW_LINES`, `TOO_MANY_LINES`, `DUPLICATE_LINE_NUMBER`, `INVALID_LINE_SIDES`, `NEGATIVE_AMOUNT`, `PRECISION_EXCEEDED`, `AMOUNT_LIMIT_EXCEEDED`, `UNSUPPORTED_CURRENCY`, `LINE_CURRENCY_MISMATCH` | Accounting rule violated |

Startup problems: *"Could not resolve placeholder LEDGER_..."* = a required environment variable is missing;
*Flyway/Hibernate validation error* = schema and entities disagree or the migration account lacks privileges;
*401 on every call* = wrong `LEDGER_JWT_ISSUER_URI` or token from another issuer.

## 11. Known limitations

- **Not compiled or executed by the author** (no Maven Central access in the generation environment); the
  Java sources were only syntax-parsed. The first `mvn clean verify` is the real quality gate.
- Maven wrapper files are not included (generate them as shown in section 1).
- Tests run on H2 in MySQL-compatibility mode, not real MySQL. Concurrency and constraint behaviour is
  tested there; run the service once against MySQL 8.0 to confirm Flyway + Hibernate validation.
- No database triggers block UPDATE/DELETE; immutability relies on `@Immutable` entities, the SELECT+INSERT-only
  application account and the absence of update/delete code paths.
- Only `INR`, one currency per entry; no multi-currency accounting or FX.
- JWT audience (`aud`) is not validated; only signature, issuer and expiry are.
- The ledger cannot verify that an `accountReference` exists in Account Service.
- Reversal is whole-entry only (no partial reversals) and a reversal cannot be reversed; to re-apply, post a new
  entry with a new business reference.
