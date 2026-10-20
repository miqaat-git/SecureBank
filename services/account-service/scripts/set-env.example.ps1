# Copy to scripts\set-env.local.ps1 (git-ignored), fill in YOUR values, then run:  . .\scripts\set-env.local.ps1
# These are PLACEHOLDERS. Never commit real passwords.

$env:ACCOUNT_DB_URL                = "jdbc:mysql://localhost:3306/securebank_account?sslMode=PREFERRED&serverTimezone=UTC"
$env:ACCOUNT_DB_USERNAME           = "account_app"
$env:ACCOUNT_DB_PASSWORD           = "<app-user-password>"
$env:ACCOUNT_DB_MIGRATION_USERNAME = "account_migrator"
$env:ACCOUNT_DB_MIGRATION_PASSWORD = "<migration-user-password>"
$env:CUSTOMER_SERVICE_BASE_URL     = "http://localhost:8081"
# Keycloak realm issuer, e.g. https://<keycloak-host>/realms/<realm>. Required. Use your real one when Keycloak exists.
$env:ACCOUNT_JWT_ISSUER_URI        = "<keycloak-issuer-uri>"
# Optional: require this value in the token's "aud" claim
# $env:ACCOUNT_JWT_AUDIENCE        = "securebank-account-service"
