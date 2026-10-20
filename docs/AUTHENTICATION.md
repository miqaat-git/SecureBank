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
| Roles | Account: realm roles `admin`, `customer`, `transaction-service` (+ client roles of `securebank-account-service`). Customer: `securebank-admin`, `securebank-staff`. Ledger: `ledger-reader`, `ledger-writer`, `ledger-reverser`. Gateway: any authenticated user (maps `ROLE_*` and `SCOPE_*` but enforces none). |
| Customer identity | Account Service reads claim `customer_id` (UUID) for CUSTOMER tokens. |
| Issuer settings documented consistently? | Four different variable names, all the same concept: `SECUREBANK_JWT_ISSUER_URI` (gateway), `CUSTOMER_JWT_ISSUER_URI`, `ACCOUNT_JWT_ISSUER_URI`, `LEDGER_JWT_ISSUER_URI`. Because one gateway forwards one token to all services, **all four must hold the identical issuer**. |

Decision: use one Keycloak realm `securebank` for all services, and **keep every service's existing role
names**. The realm defines the union of roles; no claim or role format was changed in any service.

## 3. Other confirmed defects (not changed, need a decision from you)

1. **Gateway paths do not match the services.** The gateway forwards `/api/customers/**`, `/api/accounts/**`,
   `/api/ledger/**` with no rewrite, but Customer serves `/api/v1/customers`, Account `/api/v1/accounts`
   (+ `/api/v1/admin/accounts`), and Ledger `/api/v1/journal-entries` and `/api/v1/accounts/{ref}/ledger-lines`.
   Through the gateway these calls return 404. Fix by adding path rewrites in the gateway's `RouteConfig`
   (and updating `RoutingTest`). Not done: it changes a documented public contract and the admin-route and
   Transaction Service paths are not defined yet.
2. **Customer-role tokens cannot open accounts via Account Service.** Account Service forwards the caller's
   token to Customer Service, which only accepts `securebank-staff`/`securebank-admin`. A `customer` token gets
   403 there. Giving customers the staff role would expose all customer records, so the realm does **not** do that.
   Staff/admin-created accounts work (the dev-admin user holds both role sets). A design decision is needed
   (for example a service-to-service client for the eligibility lookup).
3. Role naming differs per service (`admin` vs `securebank-admin` vs `bank-admin`). Harmless but a user who
   must act in several services needs several roles; the realm's `dev-admin` has them all.
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
reject tokens until it is reachable), but start it first to avoid confusion. Transaction Service is untouched.

## 7. Verification checklist

```powershell
# Keycloak issues the expected issuer
(Invoke-RestMethod http://localhost:8180/realms/securebank/.well-known/openid-configuration).issuer

# Ledger up (public health), then auth behaviour
Invoke-RestMethod http://localhost:8083/actuator/health
curl.exe -s -o NUL -w "%{http_code}`n" http://localhost:8083/api/v1/journal-entries                 # expect 401
$staff = .\identity\scripts\get-token.ps1 -User dev-staff
curl.exe -s -o NUL -w "%{http_code}`n" -H "Authorization: Bearer $staff" http://localhost:8083/api/v1/journal-entries   # expect 200 (reader)
curl.exe -s -o NUL -w "%{http_code}`n" -X POST -H "Authorization: Bearer $staff" -H "Content-Type: application/json" -d "{}" http://localhost:8083/api/v1/journal-entries   # expect 403 (no writer role)
$svc = .\identity\scripts\get-token.ps1 -Service      # writer: POST is allowed (a bad body then gives 400/422, not 401/403)
curl.exe -s -o NUL -w "%{http_code}`n" -X POST -H "Authorization: Bearer $svc" -H "Content-Type: application/json" -d "{}" http://localhost:8083/api/v1/journal-entries
curl.exe -s -o NUL -w "%{http_code}`n" -H "Authorization: Bearer garbage" http://localhost:8083/api/v1/journal-entries     # expect 401
```

Automated: `mvnw.cmd clean verify` in each of the four service folders.

## 8. Limitations

* Keycloak runs in `start-dev` mode (embedded database, plain http): local development only.
* The `securebank-dev-cli` client enables the password grant for test scripts. Delete it in any shared environment.
* `ACCOUNT_JWT_AUDIENCE` is off by default; the realm adds no audience claim. Enabling audience checks needs an
  audience mapper in the realm and the same value in the service.
* Defects in section 3 remain open.
