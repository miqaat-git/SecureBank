# Backend requirement-to-implementation map

Reference: `docs/blueprint/SecureBank_Blueprint_v2.3.txt` (approved; D2, D5, D6, D7 are authoritative for v1).
Status is **source-level only**: nothing here has been compiled or executed (tests and builds are deferred to the
Windows machine). "Implemented" means the code and tests exist in the repository, not that they were run.

## 1. Architecture deviation from Blueprint v2.3 (documented, not hidden)

The blueprint describes a server-rendered **JSP monolith** with one database (non-goal "Microservices", section 1).
The repository implements **five services** (API Gateway, Customer, Account, Ledger, Transaction) with **Keycloak**
authentication and one MySQL schema per service. This is a deliberate deviation, kept in this correction pass; it is
**not** the architecture the blueprint describes and is not claimed to be. Consequences:

| Blueprint | Implemented | Note |
|---|---|---|
| Monolith, JSP/WAR on Tomcat, own user store and login pages | Spring Boot services, Keycloak for login, password policy, lockout, sessions | Phase 0 WAR/JSP/Tomcat items and in-app auth screens do not apply |
| One `@Transactional` unit for funding/transfer (3.4, I5) | Distributed saga: persisted steps, idempotent remote operations, compensation, reconciliation | Equivalent intent; atomicity is achieved by idempotent steps plus rollback, not one DB transaction |
| `funding_event`, `ledger_entry`, `audit_log`, `account_status_history` tables in one DB | Funding = `transfer` row of type FUNDING (+ `audit_event`, Transaction V4); ledger entries in Ledger; `account_status_history` in Account V4 | Same facts, different tables per service |
| D3: INR in integer paise | `DECIMAL(19,2)` rupees, scale 2, no floating point, supported currency set `INR` | Exact, equivalent precision for INR |
| Ownership failures answer 404 (section 5) | A missing record and another customer's record produce the identical 404 body in Account and Transaction (`RESOURCE_NOT_FOUND` / `TRANSFER_NOT_FOUND`, `SOURCE_ACCOUNT_NOT_FOUND`); 403 stays only for genuine role failures; ownership is also enforced in the service layer | Implemented (unrun) |

## 2. Implemented

| Requirement | Where | Status |
|---|---|---|
| **D7** admin-only simulated funding; no customer deposit/withdrawal | `POST /api/v1/admin/funding` (`AdminFundingController`, `TransferService.initiateFunding`); ADMIN at URL rule **and** service layer; Gateway `POST /api/admin/funding` admin-only; customer route removed and denied at Gateway | Implemented |
| D7 positive amount <= configured max, mandatory reason, unique reference + idempotency key | `FundingRequest`, `TransferRules.validateFunding`, `FUNDING_MAX_AMOUNT`, `uk_transfer_funding_reference` (V4) | Implemented |
| D7 target must be ACTIVE; admin cannot fund own account | `TransferProcessor.checkFunding` (`FUNDING_TARGET_INELIGIBLE`, `FUNDING_OWN_ACCOUNT`); admins own no accounts (Account service refuses admin applications) | Implemented |
| D7 exactly one ledger entry per posted funding; balance delta; audit | saga LEDGER_POST (contra reference) -> CREDIT_DESTINATION; `audit_event` rows written with the state change (`TransferStore`) | Implemented (saga, see section 1) |
| D7 replay returns original; same key + other payload = 409, audited | `TransferService.existingFundingForKey`, `AuditService.recordIndependent` | Implemented |
| D7 failed attempts leave no event/ledger/balance effect; failure audited separately | validation before posting; ledger-reversal on credit failure; independent-transaction audit for denials/conflicts | Implemented |
| D7/O3 no reversal, clawback or correction of funding | none exists; recovery per blueprint 3.5 is outside the application | Consistent |
| D5 no gateways/settlement/clearing account/callbacks | settlement code, `funding_settlement`/`settlement_event` schema and config **removed**; `NoSettlementDependencyTest` | Implemented |
| **D6** application -> PENDING, zero balance; login unaffected | `AccountService.createAccount` (`Account.apply`), customers only | Implemented |
| D6 only ADMIN approves/rejects, mandatory reason, audit record | `POST /api/v1/admin/accounts/{id}/approve|reject` (URL rule + `requireAdmin`), `account_status_history` (actor, role, reason, correlation id) | Implemented |
| D6/R2 rejection terminal, reapply allowed, one PENDING per customer+type | `Account.reject`; service check under the customer lock row; unique index on generated `pending_slot` (Account V4) | Implemented (H2-tested only; MySQL index not executed) |
| D6 customer list shows PENDING/REJECTED with reason; status history API | `AccountResponse.statusReason`, `GET /api/v1/accounts/{id}/status-history` | Implemented |
| D6/R1 only ACTIVE accounts transact; FROZEN prohibited; CLOSED rules kept | `Account.credit/debit` `requireOperational`; Transaction validation `AccountView.isActive()`; PENDING/REJECTED not closable | Implemented |
| **D2** human roles CUSTOMER and ADMIN, one per user | realm export: `customer`, `admin`; `transfer-operator`, `securebank-staff`, `securebank-admin`, `bank-admin`, `dev-staff` removed; `dev-admin` has only `admin`; retry/reconcile/operations/funding/approval require `ADMIN` | Implemented in the export (a running Keycloak is not touched; see `docs/AUTHENTICATION.md`) |
| Machine roles separate | `transaction-service`, `account-service`, `ledger-*` on service accounts only; Gateway refuses machine-role tokens on public/admin routes | Implemented |
| Money: whole-paise precision, no floats | `TransferRules`, `RequestFingerprint`, `BigDecimal` | Implemented |
| Idempotent atomic transfers, compensation, reconciliation | Transaction saga, `ReconciliationService`, admin `retry`/`reconcile` | Implemented |
| Balanced append-only ledger | Ledger `JournalEntryService` and internal endpoints | Implemented |
| Concurrency-safe account number and per-customer limit | unique constraint + retry; `customer_account_locks` (V3); rejected/closed do not count | Implemented, H2-tested only |
| Least-privilege DB accounts | per-service `*_app`/`*_migrator`; `audit_event` and `account_status_history` need INSERT/SELECT only | Implemented (setup scripts) |
| Correlation id logged and propagated | servlet filter + MDC in all services, forwarded on service-to-service calls; stored on audit rows | Implemented |
| Keycloak token verification, protected `/internal/**` | per-service resource servers; Gateway denies `/internal/**` | Implemented |
| Secrets only from environment | `${...}` placeholders in the realm export, env variables | Implemented |
| Customer status audit (append-only) | Customer V3 `customer_status_audit`; `CustomerService.changeStatus` writes the row in the same transaction (actor subject + role from the token, mandatory validated reason, before/after status, correlation id, timestamp); `GET /api/v1/customers/{id}/status-audit` ADMIN only; repository has no update/delete | Implemented (unrun) |
| Read-only Account-to-Ledger reconciliation view | `GET /api/v1/admin/reconciliation/account-balances` (Transaction Service, ADMIN; Gateway `/api/admin/reconciliation/**`), backed by read-only internal endpoints `GET /internal/account-balances` (Account) and `GET /internal/ledger-balances` (Ledger); MATCH / DISCREPANCY / INCOMPLETE; nothing is written or corrected | Implemented (unrun) |
| Gateway rate limiting | `securebank.rate-limit.*`: per-rule anonymous (client address) and authenticated (validated subject) limits, 429 + `Retry-After`, trusted-proxy handling, bounded in-memory store | Implemented (unrun); per-instance only, see Gateway README |
| Funding request accepts only its own fields | request-specific `StrictFundingRequestDeserializer`; unknown fields (including `sourceAccountId`, `businessReference`) answer 400 `MALFORMED_REQUEST` without echoing values | Implemented (unrun) |

## 3. Explicitly out of scope for v1

* Payment gateways, settlement providers, bank clearing accounts, provider callbacks; a future provider integration
  would need its own approved design.
* Customer deposits and withdrawals; any funding correction, reversal or clawback feature (O3).
* A JSP monolith / in-app login screens, password policy, lockout and MFA (delegated to Keycloak configuration).
* Frontend pages (blueprint sections 6 and 7).
* Production admin bootstrap (D9): only dev users with placeholder passwords exist in the realm export.

## 4. Known gaps (not invented)

* A stored `audit_log` for *every* administrative action is not a single table: audit exists for funding,
  retry/reconcile (`audit_event`, Transaction), account status changes (`account_status_history`, Account) and
  customer status changes (`customer_status_audit`, Customer).
* Rate limiting is per Gateway instance. A deployment with more than one Gateway instance needs a shared store
  (for example Redis) provisioned deliberately and a matching `RateLimitStore` bean; none is included.
* The reconciliation view compares a snapshot taken from two services at slightly different instants; differences
  explained by unfinished transfers are reported as INCOMPLETE rather than confirmed mismatches. It never repairs.
* Nothing in this map has been executed: all Flyway migrations added in this pass (Transaction V4, Account V4,
  Customer V3) and all new tests are unrun; Maven was not available.
