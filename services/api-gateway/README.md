# SecureBank API Gateway

> **Verification status:** the build and tests were **NOT RUN** when this project was generated
> (the generation workspace could not reach Maven Central). Run `mvn -B test` locally before
> relying on it, and treat any failure as a bug to fix, not as expected. No cross-service
> integration has been tested.

## 1. Purpose and architecture

Single entry point for the SecureBank educational banking simulator (fictional customers, simulated
funds only). Reactive Spring Cloud Gateway (WebFlux/Netty) that validates JWTs and forwards
`/api/**` calls to four independent services. It holds no database and no state.

```
client --> [ API Gateway :8080 ] --> customer-service    :8081
             JWT validation    --> account-service     :8082
             correlation id    --> ledger-service      :8083
             header hygiene    --> transaction-service :8084
```

Request pipeline (in order): correlation id -> identity-header stripping -> access log ->
request-size limit -> Spring Security (CORS, JWT) -> route -> downstream.

## 2. Prerequisites

- JDK 21
- Maven 3.9+ (no Maven Wrapper is included; generate one with `mvn wrapper:wrapper` if wanted)
- Versions: Spring Boot 3.5.6, Spring Cloud 2025.0.0 (`spring-cloud-starter-gateway-server-webflux`).
  Bump to the latest 3.5.x patch and its matching Spring Cloud 2025.0.x in `pom.xml` as needed.

## 3. Environment variables (Windows PowerShell)

```powershell
$env:SECUREBANK_JWT_ISSUER_URI    = "http://localhost:8180/realms/securebank"   # REQUIRED
$env:CUSTOMER_SERVICE_BASE_URL    = "http://localhost:8081"
$env:ACCOUNT_SERVICE_BASE_URL     = "http://localhost:8082"
$env:TRANSACTION_SERVICE_BASE_URL = "http://localhost:8084"
$env:LEDGER_SERVICE_BASE_URL      = "http://localhost:8083"
$env:SECUREBANK_CORS_ALLOWED_ORIGINS = "http://localhost:3000"   # comma-separated; empty = none
$env:SECUREBANK_CONNECT_TIMEOUT   = "2s"
$env:SECUREBANK_RESPONSE_TIMEOUT  = "15s"
$env:SECUREBANK_MAX_REQUEST_BODY_SIZE = "1MB"
$env:SERVER_PORT                  = "8080"
```

The issuer URI above is an example of the intended Keycloak shape, not a configured server.
The gateway **refuses to start** if `SECUREBANK_JWT_ISSUER_URI` is empty.

## 4. Build, test, run

```powershell
mvn -B clean verify                 # compile + run all tests
mvn spring-boot:run                 # run from sources
java -jar target\api-gateway-1.0.0.jar   # run the packaged jar
```

## 5. Routes and path behavior

The public `/api/...` prefixes are kept, and each route rewrites the path to what the service really serves.

| Public path (gateway) | Rewritten to (service path) | Methods | Gateway rule | Role the service itself requires (realm role) |
|---|---|---|---|---|
| `/api/customers/**` | `/api/v1/customers/**` | all | authenticated user token | `securebank-staff` or `securebank-admin` (status PATCH: `securebank-admin`) |
| `/api/accounts/**` | `/api/v1/accounts/**` | all | authenticated user token | `admin` or `customer` (ownership checked) |
| `/api/account-numbers/**` | `/api/v1/account-numbers/**` | all | authenticated user token | `admin` or `customer` |
| `/api/admin/accounts/**` | `/api/v1/admin/accounts/**` | all | realm role **`admin`** (`ROLE_ADMIN`) and no `transaction-service` role | `admin` |
| `/api/transactions/**` | `/api/v1/transfers/**` | all | authenticated user token | `customer` to initiate; `customer` or `transfer-operator` to read; `transfer-operator` for retry/reconcile/operations (role not yet in the realm) |
| `/api/ledger/{resource}[/**]` | `/api/v1/{resource}[/**]` (e.g. `/api/ledger/journal-entries` -> `/api/v1/journal-entries`) | **GET only** | authenticated user token | any of `ledger-reader`, `ledger-writer`, `ledger-reverser` (customers get 403) |

Base URL variables: `CUSTOMER_SERVICE_BASE_URL` (`http://localhost:8081`), `ACCOUNT_SERVICE_BASE_URL` (`http://localhost:8082`;
account, account-numbers and admin routes), `TRANSACTION_SERVICE_BASE_URL` (`http://localhost:8084`),
`LEDGER_SERVICE_BASE_URL` (`http://localhost:8083`).

**Administrator role.** The gateway admin rule uses the realm role `admin`, the same role Account Service requires on
`/api/v1/admin/**` and the one the realm export gives `dev-admin`. `bank-admin` (`ROLE_BANK_ADMIN`; no rule uses it),
`securebank-admin` and `securebank-staff` (Customer Service roles) do **not** open `/api/admin/**`; accepting them
would only move the 403 downstream. Tests: `PublicApiBoundaryTest` (fixtures read the tracked realm export).

Not routable through the gateway (deny-by-default, no route exists, no upstream call is made):

| Path | Result | Why |
|---|---|---|
| `/internal/**` | 403 (401 without token) | Service-to-service contract (Account/Ledger internal adapters). Only Transaction Service calls it, directly, with its own client-credentials token. |
| `/actuator/**` (except `/actuator/health`) | 403 | Operational endpoints. |
| `/v3/api-docs*`, `/swagger-ui*` | 403 | Not public. |
| `/api/ledger/**` with POST/PUT/PATCH/DELETE | 403 | Money moves only through the Transaction saga; Ledger writes are never public. |
| raw `/api/v1/**`, bare `/api/ledger` and `/api/ledger/`, and any other path | 404 `ROUTE_NOT_FOUND` | Only the public prefixes above exist. |
| paths with `..`, `.`, `//`, `;`, `\`, or `%2e %2f %5c %00 %25` | 400 `INVALID_PATH` | Stops dot-segment/encoded traversal that could steer a rewrite toward `/internal`. |

- Service-account tokens (realm role `transaction-service`) are **refused** on the public API (403), including on `/api/admin/**` even if the token also carries `admin`: a service identity
  must not act as a user.
- The `/api/admin/accounts` and `/api/account-numbers` routes were added in checkpoint 4 because Account Service
  serves those paths; remove the blocks in `RouteConfig` if you do not want them public.
- Query strings, `Authorization`, `Idempotency-Key` and the request body pass through unchanged.
- Base URLs must be `scheme://host[:port]` with no path or query (validated at startup).
- Enforced where: authentication and the rules above in `security/SecurityConfig` (`PublicApiBoundaryTest`);
  routes and rewrites in `config/RouteConfig` (`RoutingTest`); path rejection in `web/PathSanitizationWebFilter`;
  CORS in `application.yml` (`CorsApiContractTest`). Services still enforce their own roles.

## 6. JWT and Keycloak

- Resource-server validation uses `ReactiveJwtDecoders.fromIssuerLocation(SECUREBANK_JWT_ISSUER_URI)`:
  OIDC discovery, JWK-set signature check, issuer check, expiry/not-before. Resolution is lazy, so
  the gateway starts even if the IdP is down; until it resolves, requests get `503 AUTH_PROVIDER_UNAVAILABLE`.
- Stateless: no sessions, no cookies, CSRF disabled (bearer-token API).
- The bearer token is forwarded unchanged to downstream services.
- **Keycloak:** the tracked realm export `identity/keycloak/securebank-realm.json` (realm `securebank`) defines the roles;
  set the issuer to `http://localhost:8180/realms/securebank` locally (or your Keycloak realm URL).
- **Authority mapping:** scopes -> `SCOPE_<scope>`; `realm_access.roles` -> `ROLE_<ROLE>`
  (`bank-admin` -> `ROLE_BANK_ADMIN`). The gateway *requires authentication* for all
  public routes, realm role `admin` (`ROLE_ADMIN`) for `/api/admin/**`, and refuses service-account tokens; all other role rules
  (staff, customer, transfer-operator, ledger roles) are enforced by the services.
- Audience (`aud`) validation is not enforced; add it if your Keycloak client setup needs it.
- No tokens, keys or secrets are included in this project.

## 7. CORS, timeouts, limits

- CORS: restrictive by default (no origins). Set `SECUREBANK_CORS_ALLOWED_ORIGINS`.
  Methods/headers are listed in `application.yml` (`securebank.cors.*`); credentials are off.
  Preflights are answered without a token.
- Timeouts: `SECUREBANK_CONNECT_TIMEOUT` (default 2s) and `SECUREBANK_RESPONSE_TIMEOUT` (default 15s).
  Failures return `503 SERVICE_UNAVAILABLE` (refused/unreachable), `504 GATEWAY_TIMEOUT`,
  or `502 BAD_GATEWAY` (broken/invalid downstream response).
- Request size: `413` when `Content-Length` exceeds `SECUREBANK_MAX_REQUEST_BODY_SIZE`.
- **No retries are configured.** A retried transfer without end-to-end idempotency could duplicate
  money movement. If retries are ever added: GET/HEAD only, or POST only when downstream enforces
  an `Idempotency-Key` check.
- Rate limiting is not implemented (it needs shared state). Extension point: add Spring Cloud
  Gateway's `RequestRateLimiter` filter in `RouteConfig` (requires Redis) or rate-limit at an
  upstream proxy/WAF.

## 8. Health verification

```powershell
curl.exe http://localhost:8080/actuator/health            # {"status":"UP"}
curl.exe http://localhost:8080/actuator/health/liveness
curl.exe http://localhost:8080/actuator/health/readiness
```

Only `GET /actuator/health[/**]` is public and shows no details. Every other `/actuator/**` path
is denied. Downstream health is deliberately not part of gateway health. For production, bind
management to a private port (`MANAGEMENT_SERVER_PORT`) and firewall it.

## 9. Example requests

```powershell
$TOKEN = "<access token from your identity provider>"
curl.exe -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/customers/1
curl.exe -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" `
  -H "X-Correlation-Id: demo-req-0001" -d '{\"amount\":10}' http://localhost:8080/api/transactions
curl.exe -i http://localhost:8080/api/accounts/1      # 401 UNAUTHENTICATED, no token
```

Error body (all gateway-generated errors):
`{"timestamp","status","error","code","message","correlationId"}`.

## 10. Troubleshooting

- *Gateway won't start, mentions SECUREBANK_JWT_ISSUER_URI*: set it (section 3).
- *Every request is 503 AUTH_PROVIDER_UNAVAILABLE*: the issuer URI is unreachable or its
  `/.well-known/openid-configuration` issuer value differs from the configured URI.
- *401 with a token you expect to be valid*: expired token, wrong issuer, or clock skew.
- *503 SERVICE_UNAVAILABLE*: the target service is not running at the configured base URL.
- *504*: service slower than `SECUREBANK_RESPONSE_TIMEOUT`.
- *404 ROUTE_NOT_FOUND on an `/api/...` path*: only the prefixes in section 5 are routed; raw `/api/v1/...` paths are intentionally not exposed.
- *400 INVALID_PATH*: the path contained dot segments, `//`, `;`, a backslash or an encoded `/`, `.`, `\\` or NUL.
- *403 on `/internal/...`*: by design; internal endpoints are not reachable through the gateway.
- *Startup error about a base URL*: remove any path from `*_SERVICE_BASE_URL`.
- *Browser CORS errors*: add the exact origin (scheme+host+port) to `SECUREBANK_CORS_ALLOWED_ORIGINS`.
- *Maven cannot download dependencies*: check proxy/network access to Maven Central.

## 11. Integration requirements for the downstream services

1. Serve the paths in the section 5 table (the `/api/v1/...` column), or change the rewrite in `RouteConfig` and `RoutingTest` together.
2. **Validate the JWT yourself** (same issuer) and enforce authorization. Never trust the gateway alone,
   and never trust identity from headers.
3. Return business errors as normal HTTP responses; the gateway passes them through untouched.
4. Read/propagate `X-Correlation-Id` in logs and in your own downstream calls.
5. Make financial writes idempotent (e.g. `Idempotency-Key`) before anyone considers retries.
6. Do not add your own CORS headers unless needed (duplicates are de-duplicated, first value kept).
7. Expose your own health endpoints on your own port; the gateway does not proxy them.

## 12. Known limitations and security boundaries

- The gateway is **not** the only security boundary; services must authenticate and authorize.
- It strips untrusted identity headers (`X-User-*`, `X-Customer-Id`, `X-SecureBank-*`, `X-Internal-*`,
  `X-Auth-*`, ...; see `securebank.headers.*`) and does **not** inject identity headers.
- `Content-Length` limit does not bound chunked uploads; use an edge proxy limit for that.
- `X-Forwarded-*` handling uses Spring Cloud Gateway defaults; downstream must not trust them for security.
- Only authentication is enforced at the gateway; per-role rules are left to the services.
- Keycloak is not configured here; no issuer, realm or client exists yet.
- Tests use in-JVM mock services and an in-memory JWT decoder; they prove gateway behavior, not
  real service or Keycloak integration. They were not executed during generation (see top).
- Maven Wrapper not included.
