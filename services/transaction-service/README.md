# SecureBank Transaction Service

Saga-style orchestration of money transfers between SecureBank accounts. **Educational simulator: fictional
customers, simulated funds, no real money.** Port **8084**, database `securebank_transaction`.

> **Honest status.** This code was written without access to Maven or the network, so it has **not been compiled
> or test-run by the author**. Only a Java syntax check was performed. Run `mvn test` first and fix anything it
> reports. Nothing here has been run against the real Customer, Account or Ledger services; the contracts below
> are *proposed* and must be aligned (see "Integration checklist").

## 1. Prerequisites (Windows PowerShell)

* JDK 21 (`java -version`), Maven 3.9+ (`mvn -v`), MySQL 8.0 for running (tests need no MySQL).
* No Maven wrapper is bundled (its JAR cannot be generated offline). Run `mvn wrapper:wrapper` once if you want one.

```powershell
cd transaction-service
mvn test                 # unit + integration tests on in-memory H2; never touches MySQL
mvn clean package
```

## 2. Folder structure

```
src/main/java/com/securebank/transaction/
  domain/      entities, TransferStatus, TransferStateMachine (single source of transition rules)
  repository/  Spring Data repositories (lease + recovery queries)
  service/     TransferService (commands), TransferQueryService, TransferStore/OperationStore (short DB txns),
               TransferRules (money validation), TransferCreator (race-safe idempotent create)
  workflow/    TransferProcessor (saga engine), StepExecutor (one safe remote attempt), ReconciliationService,
               RecoveryService
  client/      AccountServiceClient / LedgerServiceClient (+ HTTP impls, outcome classification)
  security/    JWT resource-server config, Keycloak role converter, caller resolution
  web/         TransferController, DTOs, GlobalExceptionHandler
src/main/resources/db/migration/V1__create_transfer_tables.sql
```

Responsibilities: this service **orchestrates** only. Account Service owns balances; Ledger Service owns the
immutable journal. This service never touches their databases.

## 3. Database setup

`docs/mysql-setup.sql` creates the database and two accounts: `tx_migrator` (DDL, used by Flyway) and `tx_app`
(`SELECT, INSERT, UPDATE` only; no DDL, no DELETE). Flyway creates the schema; Hibernate runs with
`ddl-auto=validate`. UUIDs are stored as `CHAR(36)` canonical lowercase text.

Tables: `transfer` (unique `idempotency_key`; CHECKs: amount > 0, source <> destination), `transfer_operation`
(unique `operation_id`, unique `idempotency_key`, unique `(transfer_id, operation_type)`), `transfer_history`.

## 4. Configuration (environment variables, never committed)

```powershell
$env:TRANSACTION_DB_URL="jdbc:mysql://localhost:3306/securebank_transaction"
$env:TRANSACTION_DB_USERNAME="tx_app"
$env:TRANSACTION_DB_PASSWORD="<from your password manager>"
$env:TRANSACTION_DB_MIGRATION_USERNAME="tx_migrator"
$env:TRANSACTION_DB_MIGRATION_PASSWORD="<from your password manager>"
$env:ACCOUNT_SERVICE_BASE_URL="http://localhost:8082"
$env:LEDGER_SERVICE_BASE_URL="http://localhost:8083"
$env:SECUREBANK_JWT_ISSUER_URI="<your Keycloak realm issuer URL, set when Keycloak exists>"
# optional service-to-service auth (default NONE):
$env:SERVICE_AUTH_MODE="STATIC_BEARER"; $env:SERVICE_AUTH_TOKEN="<token>"
mvn spring-boot:run
```

The service refuses to start without DB credentials and the issuer URI. `CUSTOMER_SERVICE_BASE_URL` is **not
used**: customer identity comes from the JWT and account ownership from Account Service. Endpoint paths, timeouts
and workflow tuning are in `application.yml` (`securebank.clients.*`, `securebank.transfer.*`).
`STATIC_BEARER` is a placeholder strategy (tokens expire); replace `ServiceTokenProvider` with a
client-credentials implementation when Keycloak is ready.

Health: `curl http://localhost:8084/actuator/health` (also `/health/liveness`, `/health/readiness`).
API docs: `/swagger-ui.html`, `/v3/api-docs`. Both are public; everything else requires a JWT.

## 5. API

| Method & path | Who | Result |
|---|---|---|
| `POST /api/v1/transfers` (header `Idempotency-Key`) | customer | 201 new & finished, 202 new & in progress, 200 replay, 409 key reused with different request |
| `GET /api/v1/transfers/{id}` | owner / operator | 200, 404 (also for other customers' transfers) |
| `GET /api/v1/transfers/by-idempotency-key/{key}` | owner / operator | 200 / 404 |
| `GET /api/v1/transfers?status=&sourceAccountId=&destinationAccountId=&createdFrom=&createdTo=&page=&size=` | customer (own) / operator (all) | paged, size max 100 |
| `GET /api/v1/transfers/{id}/history` | owner / operator | persisted transitions |
| `GET /api/v1/transfers/{id}/operations` | operator | persisted workflow steps |
| `POST /api/v1/transfers/{id}/retry` | operator | resume INITIATED/VALIDATING/PROCESSING/COMPENSATION_REQUIRED; else 409 |
| `POST /api/v1/transfers/{id}/reconcile` | operator | only for RECONCILIATION_REQUIRED; else 409 |

```powershell
curl -X POST http://localhost:8084/api/v1/transfers `
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: pay-2026-0001" -H "Content-Type: application/json" `
  -d '{"sourceAccountId":"11111111-1111-1111-1111-111111111111","destinationAccountId":"22222222-2222-2222-2222-222222222222","amount":250.00,"currency":"INR"}'
```
```json
{"transferId":"6b0f...","idempotencyKey":"pay-2026-0001","amount":250.00,"currency":"INR","status":"COMPLETED",
 "errorCode":null,"createdAt":"2026-10-20T10:00:00Z","completedAt":"2026-10-20T10:00:01Z", "...":"..."}
```
Errors share one shape: `{"timestamp","status","code","message","details":[{"field","message"}]}`, e.g.
`400 VALIDATION_FAILED`. A business failure such as
insufficient funds is **not** an HTTP error: it is a successfully *created* transfer whose `status` is `FAILED` with `errorCode`
`INSUFFICIENT_FUNDS`. HTTP 4xx is reserved for bad requests (400, 422 `SAME_ACCOUNT`/`UNSUPPORTED_CURRENCY`/
`INVALID_AMOUNT`/`AMOUNT_EXCEEDS_LIMIT`), auth (401/403), unknown transfer (404) and state conflicts (409).

Money rules: `BigDecimal`, > 0, at most 2 decimals (never rounded), max `1000000.00`, currency INR only.

## 6. Idempotency

`Idempotency-Key` (8-64 chars `A-Za-z0-9_.:-`) is unique in the database. The first request inserts the transfer;
concurrent duplicates hit the unique constraint, re-read the winner's row and return it as a replay (200 +
`Idempotent-Replay: true`) without starting a second workflow. A SHA-256 fingerprint of
`customer|source|destination|amount|currency` detects reuse of a key with a different request (409).

## 7. State machine

```
INITIATED -> VALIDATING | FAILED
VALIDATING -> PROCESSING | FAILED
PROCESSING -> COMPLETED | FAILED | COMPENSATION_REQUIRED | RECONCILIATION_REQUIRED
COMPENSATION_REQUIRED -> COMPENSATED | RECONCILIATION_REQUIRED
RECONCILIATION_REQUIRED -> PROCESSING | COMPENSATION_REQUIRED | FAILED   (operator reconcile only)
COMPLETED, FAILED, COMPENSATED: terminal (never changed)
```
Every transition is checked by `TransferStateMachine`, saved with a `transfer_history` row in one DB transaction.

**Workflow** (order: debit, ledger, credit; chosen so the credit, the only step that makes funds available to
someone else, happens last, after the debit and the accounting record are both durable):

1. Validate request. 2. Create/get by key. 3. VALIDATING: read both accounts (exist, ACTIVE, owned by caller for
the source, currency matches). 4. PROCESSING: `DEBIT_SOURCE` (atomic funds check in Account Service) ->
`LEDGER_POST` (balanced journal entry) -> `CREDIT_DESTINATION`. 5. **COMPLETED exactly when all three steps are
definitively SUCCEEDED.**

Each step is a durable `transfer_operation` row whose operation id is created and committed **before** the call and
reused for every retry. The attempt counter is committed before each send.

**Outcome classification** (never guessed): `SUCCEEDED`, `REJECTED` (definitive refusal), `RETRYABLE` (provably not
applied: connection refused, 401/403/429/503), `UNKNOWN` (timeouts, resets, 5xx, malformed/pending replies).
`UNKNOWN` is sticky for the step: the next cycle first queries the remote operation status, then, if still
unknown, re-sends with the **same** operation id (remote services deduplicate on it).

After `max-attempts` (default 5) inconclusive attempts:
* ambiguous history -> `RECONCILIATION_REQUIRED` (never `FAILED`, money may have moved);
* provably-not-applied only: debit step -> `FAILED`; ledger/credit step -> compensation.

**Compensation** (explicit idempotent steps; ledger entries are never edited, a reversal is a new entry):
ledger rejected -> `REVERSE_DEBIT`; credit rejected -> `LEDGER_REVERSAL` then `REVERSE_DEBIT` -> `COMPENSATED`. If a
compensating step is rejected or exhausted -> `RECONCILIATION_REQUIRED`.

This is a saga, **not** an atomic transaction: between steps the system is temporarily inconsistent and relies on
retries, recovery and reconciliation to converge.

## 8. Recovery and reconciliation

* A database **lease** (`lock_owner`, `lock_expires_at`, compare-and-set UPDATE) lets only one worker advance a
  transfer. A crashed worker's lease simply expires (default 2 min).
* On startup and every 30 s, `RecoveryService` resumes transfers in INITIATED/VALIDATING/PROCESSING/
  COMPENSATION_REQUIRED whose `next_attempt_at` is due (exponential backoff 2 s ... 5 min).
* `POST .../reconcile`: re-queries every unsettled step, persists definitive answers, then resumes or compensates.
  Inconclusive lookups change nothing. A definitively rejected compensation cannot be auto-resolved: inspect with
  `GET .../operations`, fix the cause in the owning service, then reconcile again. There is deliberately no
  "force complete" endpoint.
* Not included (documented future work): a transactional **outbox** plus RabbitMQ/Kafka could replace polling
  recovery: write an outbox row in the same transaction as each state change and publish asynchronously. Nothing
  of that exists today.

## 9. Required Account Service contract (PROPOSED; implemented in Account Service Checkpoint 2, see its README)

All paths are configurable. Internal operations must be callable by this service's service credential.

* `GET /api/v1/accounts/{accountId}` -> 200 `{"accountId","customerId","status":"ACTIVE|FROZEN|CLOSED|...","currency"}` or 404.
* `POST /internal/accounts/{accountId}/operations`, header `Idempotency-Key: <operationId>`, body
  `{"operationId","type":"DEBIT|CREDIT|REVERSE_DEBIT","amount","currency","transferId","reversesOperationId"}`.
  Must be **atomic and idempotent by operationId** (same id returns the original result, never applies twice);
  `DEBIT` must atomically check available funds. Responses: 200/201 `{"status":"SUCCEEDED"}`; definitive refusal as
  200 `{"status":"REJECTED","reasonCode":"INSUFFICIENT_FUNDS"}` or 4xx `{"code":"INSUFFICIENT_FUNDS"}`;
  409 `{"code":"OPERATION_IN_PROGRESS"}` while processing; `PENDING` is treated as unknown.
* `GET /internal/accounts/{accountId}/operations/{operationId}` -> `{"status":"SUCCEEDED|REJECTED|PENDING"}` or 404 (never recorded).

## 10. Required Ledger Service contract (PROPOSED; implemented by the Ledger internal adapter, see section 13)

* `POST /internal/journal-entries`, header `Idempotency-Key`, body `{"businessReference":"TRANSFER-<id>",
  "idempotencyKey","description","currency","entries":[{"accountId","direction":"DEBIT|CREDIT","amount"}]}` ->
  `{"status":"POSTED"}`; unique and idempotent by `businessReference`; must reject unbalanced entries.
  **Debit/credit semantics must be agreed**: this service sends DEBIT for the source and CREDIT for the destination
  from the customer's viewpoint; adjust if the ledger uses bank-side (liability) conventions.
* `POST /internal/journal-entries/{businessReference}/reversals`, body `{"reversalBusinessReference":
  "TRANSFER-<id>-REVERSAL","idempotencyKey","reason"}` -> `{"status":"POSTED"}`; creates a new reversing entry.
* `GET /internal/journal-entries/by-reference/{businessReference}` -> `{"status":"POSTED|REVERSED"}` or 404.

## 11. Security

OAuth2 resource server, stateless, JWT signature/issuer validated through `SECUREBANK_JWT_ISSUER_URI`
(discovery is lazy, on the first authenticated request). Public: health endpoints and API docs only; all else denied
by default. Expected Keycloak setup: **realm roles** `customer` and `transfer_operator` (in
`realm_access.roles`, mapped to `ROLE_CUSTOMER` / `ROLE_TRANSFER_OPERATOR`), and a user attribute/mapper that adds
the claim `customer_id` (name configurable: `securebank.security.customer-id-claim`) linking the login to the
Customer Service customer. The service never trusts a customer id from a request body. A customer token without
`customer_id` is rejected (fail closed). Customers can only create transfers from accounts whose Account
Service `customerId` equals their claim, and only read their own transfers (others look like 404).
Production security is never weakened for tests: tests use Spring Security Test's `jwt()` on the same filter chain.
No Keycloak URL, secret or token is invented here.

## 12. Known limitations / integration checklist

- [ ] **Compile and run `mvn test`** (never executed by the author); confirm Spring Boot 3.5.x and springdoc 2.8.x
      versions resolve, and bump to the latest 3.5.x patch.
- [ ] Align the Account and Ledger contracts above (paths, status values, idempotency behaviour, debit/credit sense).
- [ ] Account Service must expose atomic, idempotent debit/credit/reverse **and** operation-status lookup; without
      them this service cannot be made safe. There is no unsafe fallback.
- [ ] Keycloak roles and `customer_id` claim; replace static service token with client-credentials.
- [ ] Hibernate schema validation against MySQL (CHAR(36) UUIDs, DATETIME(6)) was verified only by design, not run.
- Recovery is polling-based; multiple instances are safe because of the DB lease, but clock skew between nodes
  affects lease expiry. Retry/lease timings are configurable.
- A rejected compensation requires human action; there is no automatic escalation or alerting yet.
- Connect timeouts surface as ambiguous (UNKNOWN) rather than "not sent": conservative on purpose.
- Contract tests against real services do not exist; the included HTTP client tests use mock servers only.

## 13. Service-to-service authentication and Ledger contract (Checkpoint 3: implemented, UNVERIFIED until built)

`SERVICE_AUTH_MODE` selects how Account and Ledger calls are authenticated:

| Mode | Behaviour |
|---|---|
| `NONE` (default) | No Authorization header. **Cannot succeed against the secured Account/Ledger internal endpoints**: they answer 401, which is classified as a retryable failure (never as success). For isolated tests only. |
| `STATIC_BEARER` | Sends `SERVICE_AUTH_TOKEN`. It expires; development only. |
| `OAUTH2_CLIENT_CREDENTIALS` | Keycloak client-credentials token, cached in memory and renewed ~60 s before expiry. Use this for any real run. |

Environment variables for `OAUTH2_CLIENT_CREDENTIALS` (names only; set values in your git-ignored `set-env.local.ps1`):

- `SERVICE_AUTH_MODE=OAUTH2_CLIENT_CREDENTIALS`
- `SERVICE_AUTH_ISSUER_URI` (e.g. `http://localhost:8180/realms/securebank`; the token endpoint is derived as `<issuer>/protocol/openid-connect/token`) or `SERVICE_AUTH_TOKEN_URI` to override it
- `SERVICE_AUTH_CLIENT_ID` (default `securebank-transaction-service`)
- `SERVICE_AUTH_CLIENT_SECRET` (the secret of that Keycloak client; the same value as `SECUREBANK_TRANSACTION_CLIENT_SECRET`)
- optional `SERVICE_AUTH_SCOPE`

The service fails fast at startup if the client id, secret or issuer is missing. No secret is logged or put in an exception.

**Roles.** The realm export already gives the service account `service-account-securebank-transaction-service` the
realm roles `transaction-service` (Account `ROLE_TRANSACTION_SERVICE`), `ledger-writer` (post) and `ledger-reverser`
(reverse); one token works for both services. Ledger reads `realm_access.roles` and, only if
`ledger.security.resource-client-id` is set, that client's `resource_access` roles. If you assign these as client roles
instead, set `ledger.security.resource-client-id` to that client id. Do not edit the realm in place; assign the roles in
the Keycloak admin console or re-import deliberately.

**Outcome classification** (what the saga does with each result):

| Remote result | Saga outcome |
|---|---|
| 2xx with `status` `SUCCEEDED` / `POSTED` / `REVERSED` | success |
| 4xx business rejection (e.g. 409 conflict, 422 validation) | rejected |
| 404 on a status lookup | not found: the operation was never recorded |
| 401/403, 429, 503, token acquisition failure, connection refused | retryable: nothing was applied |
| timeout, connection reset, 5xx, 408/502/504, 2xx without a recognised status | **unknown**: resolved by lookup, never blindly resent |

Ledger internal endpoints used (all require a Bearer token with the role shown):

| Call | Role |
|---|---|
| `POST /internal/journal-entries` | `ledger-writer` |
| `POST /internal/journal-entries/{originalBusinessReference}/reversals` | `ledger-reverser` |
| `GET /internal/journal-entries/by-reference/{businessReference}` | any Ledger role |

Verify (Windows PowerShell, from the repository root; the Maven wrapper lives in the customer-service module):

```powershell
.\services\customer-service\mvnw.cmd -f services\transaction-service\pom.xml -B -ntp verify
.\services\customer-service\mvnw.cmd -f services\ledger-service\pom.xml -B -ntp verify
```
