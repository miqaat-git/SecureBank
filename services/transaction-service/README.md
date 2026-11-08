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
| `GET /api/v1/admin/reconciliation/account-balances` | ADMIN | read-only Account-vs-Ledger comparison (section 15) |

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
  Inconclusive lookups change nothing. A definitively rejected compensation is normally terminal: inspect with
  `GET .../operations` and resolve it outside the API. There is deliberately no "force complete" endpoint.
  **One exception** (`domain/CompensationPolicy`): if the source-debit reversal was rejected with
  `ACCOUNT_NOT_OPERATIONAL` (for example the source account was frozen after the debit), an administrator can
  unfreeze the account and an operator can call reconcile again. The same operation id is re-sent (the Account
  Service records nothing for a rejected operation, so it cannot apply twice). If the account is still not usable
  the transfer simply returns to RECONCILIATION_REQUIRED. A CLOSED source account cannot be recovered this way.
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
by default. Expected Keycloak setup: **realm roles** `customer` and `admin` (the only human roles, Blueprint D2; in
`realm_access.roles`, mapped to `ROLE_CUSTOMER` / `ROLE_ADMIN`; the former `transfer-operator` role no longer exists), and a user attribute/mapper that adds
the claim `customer_id` (name configurable: `securebank.security.customer-id-claim`) linking the login to the
Customer Service customer. The service never trusts a customer id from a request body. A customer token without
`customer_id` is rejected (fail closed). Customers can only create transfers from accounts whose Account
Service `customerId` equals their claim, and only read their own transfers (others look like 404). ADMIN alone may
fund accounts, retry, reconcile and inspect workflow operations.
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

## 14. Administrator-only simulated funding (Blueprint D7)

SecureBank v1 is a fictional simulator: there are **no customer deposits or withdrawals, no payment gateway, no
settlement provider, no bank clearing account and no external callbacks**. Money enters the system only through an
administrator funding event. A future provider integration is explicitly out of scope for v1.

`POST /api/v1/admin/funding` (gateway: `POST /api/admin/funding`), human role `ADMIN` only (URL rule **and** service
check; a CUSTOMER or machine token gets 403), header `Idempotency-Key`, body
`{ "targetAccountId", "amount", "currency", "fundingReference", "reason" }`. Any other property (a source, a balance)
is a 400. There is no customer funding endpoint any more (`/api/v1/transfers/funding` and `/api/transactions/funding`
are denied).

* Validation: positive INR amount with at most 2 decimals and at most `FUNDING_MAX_AMOUNT` (default `100000.00`) per
  event; mandatory `reason` (max 255); unique `fundingReference` (8-64 chars); idempotency key scoped to the admin.
* Eligibility (checked before anything is posted): the target exists, is **ACTIVE** (PENDING, REJECTED, FROZEN, CLOSED
  are refused with `FUNDING_TARGET_INELIGIBLE`), has the same currency, and is not owned by the administrator
  (`FUNDING_OWN_ACCOUNT`; administrators cannot own customer accounts).
* Ledger: one balanced entry against the fixed ledger-only contra reference `FundingSource.CONTRA_ACCOUNT`
  (not an Account Service account), then one credit of the target. A posted funding event has exactly one ledger entry
  and one balance delta. There is no debit leg.
* Idempotency: same key and same request returns the original event (200, `Idempotent-Replay: true`); same key with a
  different target, amount, reference or reason returns **409** `IDEMPOTENCY_KEY_REUSED` and is audited; a reused
  `fundingReference` under a new key returns 409 `FUNDING_REFERENCE_REUSED`.
* Audit: table `audit_event` (append-only; V4). `FUNDING_REQUESTED` is written in the same transaction as the funding
  record; `FUNDING_POSTED` / `FUNDING_FAILED` / `FUNDING_COMPENSATED` / `FUNDING_RECONCILIATION_REQUIRED` in the same
  transaction as the state change; denials, validation failures and conflicts (`FUNDING_DENIED`, `FUNDING_REJECTED`,
  `FUNDING_CONFLICT`) in their own transaction so they survive the rollback. Admin retry/reconcile are audited too.
* Failed attempts: a validation or ledger failure leaves no balance change. If the credit is rejected after the ledger
  post, the saga reverses the ledger entry (`COMPENSATED`) so the net effect is zero; an unknown outcome goes to
  `RECONCILIATION_REQUIRED` for an administrator (`POST /api/v1/transfers/{id}/reconcile`). This internal rollback of a
  failed attempt is not a funding correction: **v1 has no reversal, clawback or correction of a posted funding event**
  (blueprint O3); escalation and recovery are as documented in blueprint section 3.5 (a restore or rebuild by the
  system owner outside the application).

Architecture deviation: the blueprint runs funding insert, ledger entry, balance delta and audit in one database
transaction. With separate Ledger and Account services this is a saga with idempotent steps, compensation and
reconciliation instead (see `docs/BACKEND_REQUIREMENTS_MAP.md`).

Configuration: `FUNDING_ENABLED` (default `true`) and `FUNDING_MAX_AMOUNT`. Nothing else; no provider or clearing
account settings exist.

Migration `V4__admin_funding_and_audit.sql` adds `funding_reference`, `funding_reason`, `funding_actor_customer_id` to
`transfer` and creates `audit_event`. The application account needs SELECT, INSERT, UPDATE on `transfer` and only
SELECT, INSERT on `audit_event`.

## 15. Account-to-Ledger reconciliation view (read only, ADMIN)

`GET /api/v1/admin/reconciliation/account-balances?status=&currency=&accountId=&result=&page=&size=` (Gateway:
`GET /api/admin/reconciliation/account-balances`). ADMIN only: URL rule **and** service check (customer, machine and
anonymous callers get 403/401). Page size 1-100 (default 50), ordered by account id.

* Sources (internal APIs only, no cross-service database access): Account `GET /internal/account-balances`
  (ROLE_TRANSACTION_SERVICE; id, currency, status, balance - no customer data) and Ledger
  `GET /internal/ledger-balances?accountIds=` (any Ledger machine role; grouped posted-line totals).
* Ledger semantics used: every ledger entry is POSTED; a reversal is an ordinary entry mirroring each line with debit
  and credit swapped, so summing all lines nets a reversal against its original exactly once (no double counting).
  A customer account is a liability: `ledgerBalance = sum(credits) - sum(debits)` in the account's currency;
  `difference = accountBalance - ledgerBalance`. Accounts in any lifecycle state are included (PENDING/REJECTED
  accounts normally have no lines and a zero balance, so they match).
* Per row: `accountId, currency, accountStatus, accountBalance, ledgerBalance, difference, result, reasonCode,
  ledgerLineCount, accountReadAt`. Report header: `generatedAt, ledgerReadAt, correlationId, basis, paging, summary`.
* `result`: **MATCH** (difference is zero); **DISCREPANCY** (confirmed: non-zero difference and no unfinished
  transfer touches the account; `reasonCode` `BALANCE_MISMATCH` or `NO_LEDGER_ENTRIES`); **INCOMPLETE** (cannot be
  confirmed: `UNFINISHED_TRANSFERS` - an INITIATED/VALIDATING/PROCESSING/COMPENSATION_REQUIRED/RECONCILIATION_REQUIRED
  transfer involves the account and the two reads were not atomic - or `LEDGER_HAS_OTHER_CURRENCY_LINES`). INCOMPLETE
  is never reported as a match.
* `result` filter narrows the rows of the requested account page only; `summary` counts the whole page.
* If Account or Ledger cannot be read the answer is `503` with code `ACCOUNT_SERVICE_UNAVAILABLE` or
  `LEDGER_SERVICE_UNAVAILABLE` (no partial report).
* Strictly read only: no write to any service, no balance correction, no artificial balancing entry; the ledger stays
  immutable. Repair of a confirmed discrepancy is an administrator decision outside this view (existing retry/reconcile
  for unfinished transfers).
* The view reads one page of accounts then one Ledger batch; it is a point-in-time comparison, not a transaction across
  services. Re-run it after unfinished transfers settle.

## 16. Funding request validation

`FundingRequest` is bound by a request-specific deserializer (`StrictFundingRequestDeserializer`), because Spring Boot
disables `FAIL_ON_UNKNOWN_PROPERTIES` globally. Only `targetAccountId, amount, currency, fundingReference, reason` are
accepted. Any other property (`sourceAccountId`, `businessReference`, a balance, ...), a non-object body or malformed
JSON answers `400 MALFORMED_REQUEST` in the standard error shape; the rejected property name and value are neither
echoed nor logged. Other endpoints keep the global lenient mapper.

## 17. Ownership failures

A transfer, history or source account that does not exist and one that belongs to another customer produce the same
`404` body (`TRANSFER_NOT_FOUND`, or `SOURCE_ACCOUNT_NOT_FOUND` when initiating a transfer from an account the caller
does not own). `403` is reserved for genuine role failures (for example a customer calling an ADMIN endpoint) and `401`
for missing/invalid tokens. Ownership is enforced in `TransferService` and `TransferQueryService`, not only by URL rules.
