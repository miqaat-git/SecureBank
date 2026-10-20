<#
 Prints an access token from the local Keycloak, for manual API testing. DEVELOPMENT ONLY.
   $t = .\identity\scripts\get-token.ps1 -User dev-admin          # prompts for the password
   $t = .\identity\scripts\get-token.ps1 -Service                 # transaction-service client credentials
   Invoke-RestMethod http://localhost:8083/api/v1/journal-entries -Headers @{ Authorization = "Bearer $t" }
 The token is returned, not written to disk. Do not commit or share it.
#>
param(
    [ValidateSet('dev-admin','dev-staff','dev-customer')][string]$User = 'dev-admin',
    [switch]$Service,
    [string]$Issuer = 'http://localhost:8180/realms/securebank'
)
$ErrorActionPreference = 'Stop'
$uri = "$Issuer/protocol/openid-connect/token"
if ($Service) {
    if (-not $env:SECUREBANK_TRANSACTION_CLIENT_SECRET) { throw 'Set SECUREBANK_TRANSACTION_CLIENT_SECRET first.' }
    $body = @{ grant_type = 'client_credentials'; client_id = 'securebank-transaction-service';
               client_secret = $env:SECUREBANK_TRANSACTION_CLIENT_SECRET }
} else {
    $secure = Read-Host -AsSecureString "Password for $User"
    $plain  = [System.Net.NetworkCredential]::new('', $secure).Password
    $body = @{ grant_type = 'password'; client_id = 'securebank-dev-cli'; username = $User; password = $plain }
}
(Invoke-RestMethod -Method Post -Uri $uri -Body $body -ContentType 'application/x-www-form-urlencoded').access_token
