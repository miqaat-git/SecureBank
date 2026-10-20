<#
 Starts the local Keycloak (development mode, port 8180) and imports the securebank realm on FIRST start.
 Prerequisites: install-keycloak.ps1 has run, and the secrets from set-keycloak-env.example.ps1 are set
 in this PowerShell window. Leave this window open; Keycloak runs in it. Stop with Ctrl+C.
 Issuer of all tokens afterwards:  http://localhost:8180/realms/securebank
 To re-import the realm from scratch, stop Keycloak and delete identity\.keycloak\keycloak-26.4.0\data.
 DEVELOPMENT ONLY: start-dev uses an embedded file database and plain http.
#>
$ErrorActionPreference = 'Stop'
$version = '26.4.0'
$root = Split-Path -Parent $PSScriptRoot
$home_ = Join-Path $root ".keycloak\keycloak-$version"
if (-not (Test-Path (Join-Path $home_ 'bin\kc.bat'))) { throw 'Keycloak is not installed. Run identity\scripts\install-keycloak.ps1 first.' }

$required = 'KC_BOOTSTRAP_ADMIN_USERNAME','KC_BOOTSTRAP_ADMIN_PASSWORD','SECUREBANK_DEV_ADMIN_PASSWORD',
            'SECUREBANK_DEV_STAFF_PASSWORD','SECUREBANK_DEV_CUSTOMER_PASSWORD','SECUREBANK_TRANSACTION_CLIENT_SECRET'
$missing = $required | Where-Object { -not [Environment]::GetEnvironmentVariable($_) }
if ($missing) { throw "Missing environment variables: $($missing -join ', '). See identity\scripts\set-keycloak-env.example.ps1" }

$import = Join-Path $home_ 'data\import'
New-Item -ItemType Directory -Force -Path $import | Out-Null
Copy-Item (Join-Path $root 'keycloak\securebank-realm.json') $import -Force

& (Join-Path $home_ 'bin\kc.bat') start-dev --http-port=8180 --hostname=http://localhost:8180 --import-realm
