<#
.SYNOPSIS
  Read-only status of the local SecureBank stack: MySQL service, Keycloak, the five services, and the Gateway's
  anonymous behaviour. Prints no secrets and changes nothing.

.EXAMPLE
  .\scripts\check-health.ps1
#>
$ErrorActionPreference = 'Continue'

function Test-Listening([int] $port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

function Get-Health([int] $port) {
    try {
        $r = Invoke-RestMethod -Uri "http://localhost:$port/actuator/health" -TimeoutSec 3
        return [string]$r.status
    } catch {
        return 'no UP response'
    }
}

function Get-StatusCode([string] $uri) {
    try {
        return [string](Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec 5).StatusCode
    } catch {
        if ($_.Exception.Response) { return [string][int]$_.Exception.Response.StatusCode }
        return 'no response'
    }
}

$mysql = Get-Service -Name 'MySQL80' -ErrorAction SilentlyContinue
$mysqlState = if ($mysql) { [string]$mysql.Status } else { 'service not found' }

$services = @(
    @{ Name = 'gateway'; Port = 8080 }, @{ Name = 'customer'; Port = 8081 }, @{ Name = 'account'; Port = 8082 },
    @{ Name = 'ledger'; Port = 8083 }, @{ Name = 'transaction'; Port = 8084 })
$rows = foreach ($s in $services) {
    $listening = Test-Listening $s.Port
    [pscustomobject]@{
        Component = $s.Name
        Port      = $s.Port
        Listening = $listening
        Health    = $(if ($listening) { Get-Health $s.Port } else { '-' })
    }
}
$keycloakListening = Test-Listening 8180
$realmCode = if ($keycloakListening) { Get-StatusCode 'http://localhost:8180/realms/securebank/.well-known/openid-configuration' } else { '-' }
$rows = @($rows) + @([pscustomobject]@{
        Component = 'keycloak (realm securebank discovery)'; Port = 8180; Listening = $keycloakListening; Health = $realmCode })
$rows | Format-Table -AutoSize

Write-Host "MySQL80 service: $mysqlState"
if (Test-Listening 8080) {
    Write-Host ("Gateway, anonymous GET /api/customers (expect 401): " + (Get-StatusCode 'http://localhost:8080/api/customers'))
    Write-Host ("Gateway, anonymous GET /internal/accounts/x (expect 401 or 403, never 200/404): " +
        (Get-StatusCode 'http://localhost:8080/internal/accounts/x'))
}
