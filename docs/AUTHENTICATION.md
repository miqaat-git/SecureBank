# SecureBank authentication: audit, design and setup

Audit date 2026-10-19, based on the source in the four supplied archives only.

## 1. Root cause

Ledger Service's `application.yml` sets `spring.security.oauth2.resourceserver.jwt.issuer-uri: ${LEDGER_JWT_ISSUER_URI}`
with no default, and the variable was not set in the PowerShell session, so Spring Boot could not build the
JWT decoder. That is a configuration failure, not a code defect. The deeper problem is that **no identity
provider exists in the project**, so there was no real value to put in the variable.

## 2. Authentication architecture actually found

| Question | Finding (source evidence) |
|---|---|
| Who issues JWTs? | **Nothing.** No token endpoint, no signing code, no `JwtEncoder`, no Keycloak realm or compose file in any archive. |
| Custom issuer or Keycloak? | All four services are written as pure OAuth2 resource servers for **Keycloak-shaped tokens** (`realm_access.roles`, `resource_access`). READMEs call Keycloak "future" / "not configured". This is Case C (no issuer exists). |
| Issuer value | Not defined anywhere. Gateway README gives `http://localhost:8180/realms/securebank` as an example shape only. |
| Signature validation | Every service discovers the JWKS from the issuer (OIDC discovery). Gateway and Account/Customer decoders are lazy (start without the IdP, fail closed); Ledger uses Spring Boot's issuer-uri auto-configuration. Nobody holds keys locally. |
| Who validates independently | All four: Gateway (reactive), Customer, Account, Ledger. Gateway forwards the `Authorization` header unchanged and injects no identity headers; it strips spoofable identity headers. |
| Roles | Human: realm roles `customer` and `admin` only (D2). Account: `admin`, `customer`, `transaction-service` (+ client roles of `securebank-account-service`). Customer Service maps `admin` to its staff/admin authorities. Ledger: `ledger-reader`, `ledger-writer`, `ledger-reverser`. Gateway: any authenticated user token, except realm role `admin` for `/api/admin/**`, no `/internal/**`, GET-only ledger, and service-account tokens refused (see `services/api-gateway/README.md` section 5). |
| Customer identity | Account Service reads claim `customer_id` (UUID) for CUSTOMER tokens. |
| Issuer settings documented consistently? | Four different variable names, all the same concept: `SECUREBANK_JWT_ISSUER_URI` (gateway), `CUSTOMER_JWT_ISSUER_URI`, `ACCOUNT_JWT_ISSUER_URI`, `LEDGER_JWT_ISSUER_URI`. Because one gateway forwards one token to all services, **all four must hold the identical issuer**. |

Decision: use one Keycloak realm `securebank` for all services, and **keep every service's existing role
names**. The realm defines the union of roles; no claim or role format was changed in any service.

## 3. Other confirmed defects (not changed, need a decision from you)

1. ~~Gateway paths do not match the services.~~ Resolved in checkpoint 4: the gateway rewrites the public
   `/api/...` prefixes to the services' `/api/v1/...` paths and does not route `/internal/**`
   (see `services/api-gateway/README.md` section 5).
2. ~~Customer-role tokens cannot open accounts.~~ Resolved: Account Service now calls Customer Service's internal
   `GET /internal/v1/customers/{id}/eligibility` with its OWN client-credentials token (client
   `securebank-account-service`, realm role `account-service`); the end user's token is never forwarded. The owner of a
   new account is the token's `customer_id`. Needs the realm client/role (see the realm export) and
   `ACCOUNT_SERVICE_AUTH_CLIENT_SECRET`.
3. ~~Role naming differs per service.~~ Resolved (D2): the realm has the human roles `customer` and `admin` only; Customer
   Service now reads `admin` for both its staff and admin authorities.
4. Ledger's README line "JDBC URL `localhost`" with MySQL users created for host `127.0.0.1` can cause
   "access denied" on Windows (IPv6 `::1`). The template uses `127.0.0.1`.

## 4. What was changed

See `CHANGES.md`.

## 5. Environment variables

| Variable | Service | Purpose / format | Mandatory | Source of value | Safe to commit? |
|---|---|---|---|---|---|
| `SECUREBANK_JWT_ISSUER_URI` | Gateway | Token issuer, `http(s)://host/realms/securebank`, no trailing `/` | Yes | Your Keycloak | Example yes |
| `CUSTOMER_JWT_ISSUER_URI` | Customer | same | No (empty rejects all tokens) | same | Example yes |
| `ACCOUNT_JWT_ISSUER_URI` | Account | same | Yes | same | Example yes |
| `ACCOUNT_JWT_AUDIENCE` | Account | Required `aud` value | No | You | Yes |
| `LEDGER_JWT_ISSUER_URI` | Ledger | same | Yes | same | Example yes |
| `LEDGER_DB_URL` | Ledger | `jdbc:mysql://127.0.0.1:3306/securebank_ledger?serverTimezone=UTC` | Yes | Your MySQL | Example yes |
| `LEDGER_DB_USERNAME` / `_PASSWORD` | Ledger | Runtime account `ledger_app` (SELECT, INSERT) | Yes | Password you set in the SQL script | **Password never** |
| `LEDGER_DB_MIGRATION_USERNAME` / `_PASSWORD` | Ledger | Flyway account `ledger_migrator` | Yes | same | **Password never** |
| `ACCOUNT_DB_*`, `CUSTOMER_DB_*`, `*_SERVICE_BASE_URL` | Account, Customer, Gateway | see each service README | per README | per README | passwords never |
| `KC_BOOTSTRAP_ADMIN_USERNAME/_PASSWORD` | Keycloak | Admin console login | Yes (first start) | You choose | **Never** |
| `SECUREBANK_DEV_{ADMIN,STAFF,CUSTOMER}_PASSWORD` | Keycloak | Dev user passwords, imported once | Yes (first start) | You choose | **Never** |
| `SECUREBANK_TRANSACTION_CLIENT_SECRET` | Keycloak | Secret of the `securebank-transaction-service` client | Yes (first start) | You generate | **Never** |
| `SECUREBANK_ACCOUNT_CLIENT_SECRET` | Keycloak | Secret of the `securebank-account-service` client (same value as the Account Service's `ACCOUNT_SERVICE_AUTH_CLIENT_SECRET`) | Yes (first start) | You generate, different from the transaction secret | **Never** |
| `ACCOUNT_SERVICE_AUTH_CLIENT_SECRET` | Account Service | Secret it presents to Keycloak (client `securebank-account-service`, client-credentials) for its Customer Service lookup; must equal `SECUREBANK_ACCOUNT_CLIENT_SECRET` | Yes (the service refuses to start without it) | Same value you generated for Keycloak | **Never** |
| `SECUREBANK_DEV_CUSTOMER_ID` | Keycloak | UUID in dev-customer's `customer_id` claim | No (a placeholder UUID is used) | A real customer id | Yes |

Template: `scripts\set-env.example.ps1`.

### Three kinds of environment variable

* **Current PowerShell session:** `$env:NAME = "value"`. Lives only in that window and its child processes (such as `mvnw.cmd`). Gone when you close it. Recommended here, via the dot-sourced local script.
* **Persistent Windows user variable:** `[Environment]::SetEnvironmentVariable("NAME","value","User")` or the Windows settings dialog. Survives restarts but only new windows see it, and secrets are then stored in your user profile; use it only for non-secret values such as the issuer URL.
* **Container/deployment:** set by the platform (`environment:` in compose, Kubernetes secrets, a CI vault). Not used locally here because Docker is not assumed.

## 6. Startup order (Windows PowerShell, from the SecureBank root folder)

Prerequisites: JDK 21 (`java -version`), MySQL 8 running, no Docker needed.

```powershell
# 0. one-time: copy and fill the two templates (git-ignored copies)
Copy-Item .\scripts\set-env.example.ps1 .\scripts\set-env.local.ps1
Copy-Item .\identity\scripts\set-keycloak-env.example.ps1 .\identity\scripts\set-keycloak-env.local.ps1
notepad .\scripts\set-env.local.ps1
notepad .\identity\scripts\set-keycloak-env.local.ps1

# 1. one-time: install Keycloak 26.4.0 (checksum-verified download)
.\identity\scripts\install-keycloak.ps1

# 2. window A: start Keycloak and leave it running
. .\identity\scripts\set-keycloak-env.local.ps1
.\identity\scripts\start-keycloak.ps1
#    ready when http://localhost:8180/realms/securebank/.well-known/openid-configuration opens

# 3. one-time: create the Ledger database and its two users (edit the passwords in the SQL first)
Get-Content .\ledger-service\docs\database\01-create-database-and-users.sql | mysql -u root -p

# 4. window B: Ledger Service
. .\scripts\set-env.local.ps1
cd .\ledger-service
.\mvnw.cmd spring-boot:run        # if you have no wrapper yet: mvn -N wrapper:wrapper -Dmaven=3.9.11
```

Start Customer (8081), Account (8082) and then the Gateway (8080) the same way, each in its own window after
dot-sourcing `scripts\set-env.local.ps1`. Services start even if Keycloak is down (they fail closed and
reject tokens until it is reachable), but start it first to avoid confusion. Then start Transaction (8084) last. For checkpoint 3/4 it needs
`SERVICE_AUTH_MODE=OAUTH2_CLIENT_CREDENTIALS` plus the client id/secret/token URI variables from
`scripts\set-env.example.ps1` (secret from your local, git-ignored file only).

**One-command alternative** (does the same without hand-opening windows): with MySQL80 and Keycloak already running,

```powershell
.\scripts\start-services.ps1 -CheckOnly   # names-only check of variables, Maven, MySQL, Keycloak and ports
.\scripts\start-services.ps1               # customer, account, ledger, transaction, gateway, each in its own window
.\scripts\check-health.ps1                 # read-only status table at any time
```

Each service window stays open if the service crashes, so the error remains visible; closing a window stops that
service. A port that is already listening is skipped. Maven is found automatically
(`services\customer-service\mvnw.cmd`, `mvn` on PATH, or NetBeans' bundled Maven); use `-Maven <path>` to override.

**One-command alternative** (does the same without hand-opening windows): with MySQL80 and Keycloak already running,

```powershell
.\scripts\start-services.ps1 -CheckOnly   # names-only check of variables, Maven, MySQL, Keycloak and ports
.\scripts\start-services.ps1               # customer, account, ledger, transaction, gateway, each in its own window
.\scripts\check-health.ps1                 # read-only status table at any time
```

Each service window stays open if the service crashes, so the error remains visible; closing a window stops that
service. A port that is already listening is skipped. Maven is found automatically
(`services\customer-service\mvnw.cmd`, `mvn` on PATH, or NetBeans' bundled Maven); use `-Maven <path>` to override.

## 7. Verification checklist

```powershell
# Keycloak issues the expected issuer
(Invoke-RestMethod http://localhost:8180/realms/securebank/.well-known/openid-configuration).issuer

# Ledger up (public health), then auth behaviour
Invoke-RestMethod http://localhost:8083/actuator/health
curl.exe -s -o NUL -w "%{http_code}`n" http://localhost:8083/api/v1/journal-entries                 # expect 401
$cust = .\identity\scripts\get-token.ps1 -User dev-customer
curl.exe -s -o NUL -w "%{http_code}`n" -H "Authorization: Bearer $cust" http://localhost:8083/api/v1/journal-entries   # expect 403 (humans hold no ledger role)
curl.exe -s -o NUL -w "%{http_code}`n" -X POST -H "Authorization: Bearer $cust" -H "Content-Type: application/json" -d "{}" http://localhost:8083/api/v1/journal-entries   # expect 403 (no writer role)
$svc = .\identity\scripts\get-token.ps1 -Service      # writer: POST is allowed (a bad body then gives 400/422, not 401/403)
curl.exe -s -o NUL -w "%{http_code}`n" -X POST -H "Authorization: Bearer $svc" -H "Content-Type: application/json" -d "{}" http://localhost:8083/api/v1/journal-entries
curl.exe -s -o NUL -w "%{http_code}`n" -H "Authorization: Bearer garbage" http://localhost:8083/api/v1/journal-entries     # expect 401
```

Automated: `mvnw.cmd clean verify` in each of the four service folders.

### Human roles (Blueprint D2)

The only human realm roles are `customer` and `admin`, one per user. The former `transfer-operator`, `securebank-staff`,
`securebank-admin` and `bank-admin` roles were removed from the realm export; `admin` now covers administrative retry,
reconcile, funding and account approval. Technical roles (`transaction-service`, `account-service`, `ledger-*`) belong
to service accounts only. Administrators must not own bank accounts, so an `admin` user has no `customer_id` claim.

`--import-realm` only imports a realm that does not exist yet, so an already-running local Keycloak keeps its old roles
until you change it by hand (this repository never modifies a live Keycloak). To align a running realm, in the admin
console (`realm securebank`): remove the legacy roles from users, give each human user exactly one of `customer` or
`admin`, and delete the legacy roles when no user holds them. Tokens are read at login, so fetch a fresh token afterwards.

## 7a. Money-flow runbook and synthetic end-to-end procedure (checkpoint 4; NOT yet executed)

Ports: Keycloak 8180, Gateway 8080, Customer 8081, Account 8082, Ledger 8083, Transaction 8084.
Health (public): `http://localhost:<port>/actuator/health`; through the gateway only `http://localhost:8080/actuator/health`.

Realm facts to know before testing:
* The `transaction-service` service account holds `transaction-service`, `ledger-writer`, `ledger-reverser`.
  Transaction Service obtains its token itself; callers never send it.
* The human role `admin` (maps to `ROLE_ADMIN`) is required by Transaction's funding, retry, reconcile and operations
  endpoints and by Account Service's approve/reject endpoints. `dev-admin` holds only `admin` (D2: one role per user).
* `securebank-web` allows only `http://localhost:3000`. A Vite front end on `http://localhost:5173` needs that
  origin added as redirect URI, web origin and post-logout redirect (proposal, not applied) and in
  `SECUREBANK_CORS_ALLOWED_ORIGINS` for the gateway.
* Tokens carry no audience; none is enforced.

Procedure (use only synthetic data in a development database; never paste tokens into notes or tickets):
1. Start everything in the order of section 6; confirm all health endpoints are UP.
2. Obtain a `dev-customer` token and a `dev-admin` token with `identity\scripts\get-token.ps1` (kept in variables only).
3. Create two customers through the gateway (`POST /api/customers`, needs the Customer Service admin role mapping in your
   environment), then each customer applies for an account with their own token: `POST /api/accounts`. The account is
   created `PENDING` with a zero balance and cannot send, receive or be funded yet.
4. As `dev-admin`: approve each application with `POST /api/admin/accounts/{id}/approve` and a body
   `{"reason":"..."}` (or reject with `/reject`; a rejection is final for that application, the customer may apply
   again). Then fund an account with `POST /api/admin/funding` and a body
   `{"targetAccountId","amount","currency":"INR","fundingReference":"<unique>","reason":"..."}` plus an
   `Idempotency-Key` header (Transaction README section 14). Funding is simulated; there is no provider.
5. `POST /api/transactions` through the gateway with a customer token, header `Idempotency-Key: <new uuid>` and body
   `sourceAccountId`, `destinationAccountId`, `amount`, `currency`. Expect 201 (completed) or 202 (in progress).
   Repeat the identical request: expect 200 with `Idempotent-Replay: true` and no second movement.
6. Verify: both balances via `GET /api/accounts/{id}` (customer token for an owned account, or `dev-admin`);
   the transfer via `GET /api/transactions/{id}` (customer token; `dev-admin` is refused by Transaction Service);
   the journal entry via `GET /api/ledger/journal-entries` with the `dev-admin` token (holds `ledger-reader`; a customer token gets 403).
7. Negative checks: `/internal/accounts/{id}` and `/api/v1/accounts` through port 8080 must not succeed (403/404);
   `POST /api/ledger/journal-entries` must be 403.

Known gaps: no funding workflow; a `customer` token cannot open accounts (defect 2); the policy for reversing
a frozen source is undecided; Account V2 and Ledger concurrency were only tested on H2.

## 8. Limitations

* Keycloak runs in `start-dev` mode (embedded database, plain http): local development only.
* The `securebank-dev-cli` client enables the password grant for test scripts. Delete it in any shared environment.
* `ACCOUNT_JWT_AUDIENCE` is off by default; the realm adds no audience claim. Enabling audience checks needs an
  audience mapper in the realm and the same value in the service.
* Defects in section 3 remain open.
