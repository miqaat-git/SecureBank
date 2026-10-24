# SecureBank - environment template for ALL services (Windows PowerShell).
#
#   1. Copy to scripts\set-env.local.ps1 (git-ignored) and replace every <placeholder>.
#   2. In EACH PowerShell window you start a service from, run:   . .\scripts\set-env.local.ps1
#      (the leading dot matters: it applies the variables to the current window).
#
# These variables live only in the current PowerShell session. They disappear when you close the window.
# Never commit set-env.local.ps1, and never paste real passwords into this example file.
# Per-service values are only needed by that service; the shared Keycloak URL is needed by all four.

# ---- Shared identity provider (all four services must use the SAME issuer) --------------------------
# Local Keycloak from identity\README.md. Must equal the "iss" claim of the tokens, no trailing slash.
$Issuer = "http://localhost:8180/realms/securebank"
$env:SECUREBANK_JWT_ISSUER_URI = $Issuer   # API Gateway   (mandatory)
$env:CUSTOMER_JWT_ISSUER_URI   = $Issuer   # Customer Svc  (optional: empty = rejects every token)
$env:ACCOUNT_JWT_ISSUER_URI    = $Issuer   # Account Svc   (mandatory)
$env:LEDGER_JWT_ISSUER_URI     = $Issuer   # Ledger Svc    (mandatory)

# ---- Ledger Service (port 8083, database securebank_ledger) -----------------------------------------
# Use 127.0.0.1, not localhost: the MySQL users are created for host '127.0.0.1'.
$env:LEDGER_DB_URL                = "jdbc:mysql://127.0.0.1:3306/securebank_ledger?serverTimezone=UTC"
$env:LEDGER_DB_USERNAME           = "ledger_app"
$env:LEDGER_DB_PASSWORD           = "<ledger_app password>"
$env:LEDGER_DB_MIGRATION_USERNAME = "ledger_migrator"
$env:LEDGER_DB_MIGRATION_PASSWORD = "<ledger_migrator password>"

# ---- Account Service (port 8082) -------------------------------------------------------------------
$env:ACCOUNT_DB_URL                = "jdbc:mysql://localhost:3306/securebank_account?sslMode=PREFERRED&serverTimezone=UTC"
$env:ACCOUNT_DB_USERNAME           = "account_app"
$env:ACCOUNT_DB_PASSWORD           = "<account_app password>"
$env:ACCOUNT_DB_MIGRATION_USERNAME = "account_migrator"
$env:ACCOUNT_DB_MIGRATION_PASSWORD = "<account_migrator password>"
$env:CUSTOMER_SERVICE_BASE_URL     = "http://localhost:8081"
# Optional: require this value in the token's "aud" claim (the realm does not add one by default).
# $env:ACCOUNT_JWT_AUDIENCE = "<audience>"

# ---- Customer Service (port 8081) ------------------------------------------------------------------
$env:CUSTOMER_DB_USERNAME           = "<customer_app user>"
$env:CUSTOMER_DB_PASSWORD           = "<customer_app password>"
$env:CUSTOMER_DB_MIGRATION_USERNAME = "<customer_migrator user>"
$env:CUSTOMER_DB_MIGRATION_PASSWORD = "<customer_migrator password>"
# $env:CUSTOMER_DB_URL = "jdbc:mysql://localhost:3306/securebank_customer"   # default shown

# ---- Transaction Service (port 8084) ---------------------------------------------------------------
# Service-to-service authentication. NONE cannot authenticate to the secured Account/Ledger internal endpoints.
$env:SERVICE_AUTH_MODE          = "OAUTH2_CLIENT_CREDENTIALS"
$env:SERVICE_AUTH_ISSUER_URI    = $Issuer                                  # token endpoint is derived from this
$env:SERVICE_AUTH_CLIENT_ID     = "securebank-transaction-service"
$env:SERVICE_AUTH_CLIENT_SECRET = "<secret of the securebank-transaction-service Keycloak client>"
# The Transaction Service also needs its own database and issuer (see services\transaction-service\README.md).

# ---- API Gateway (port 8080) -----------------------------------------------------------------------
$env:ACCOUNT_SERVICE_BASE_URL  = "http://localhost:8082"
$env:LEDGER_SERVICE_BASE_URL   = "http://localhost:8083"
# $env:SECUREBANK_CORS_ALLOWED_ORIGINS = "http://localhost:3000"   # Vite default is http://localhost:5173 - use the exact origin your front end runs on
