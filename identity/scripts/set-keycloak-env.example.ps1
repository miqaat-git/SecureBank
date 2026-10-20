# Keycloak (identity provider) secrets for LOCAL DEVELOPMENT.
# Copy to identity\scripts\set-keycloak-env.local.ps1 (git-ignored), replace every <placeholder> with a
# password YOU choose (12+ characters), then run:  . .\identity\scripts\set-keycloak-env.local.ps1
# They are read once, when the realm is imported on first start. Nothing here is committed or built in.

$env:KC_BOOTSTRAP_ADMIN_USERNAME          = "kcadmin"
$env:KC_BOOTSTRAP_ADMIN_PASSWORD          = "<securebank>"
$env:SECUREBANK_DEV_ADMIN_PASSWORD        = "<securebank>"
$env:SECUREBANK_DEV_STAFF_PASSWORD        = "<securebank>"
$env:SECUREBANK_DEV_CUSTOMER_PASSWORD     = "<securebank>"
$env:SECUREBANK_TRANSACTION_CLIENT_SECRET = "<random secret, 32+ characters>"
# Optional: UUID of a real customer created in Customer Service. Becomes dev-customer's customer_id claim.
# $env:SECUREBANK_DEV_CUSTOMER_ID = "<customer uuid>"
