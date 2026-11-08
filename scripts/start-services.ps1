<#
.SYNOPSIS
  Starts the SecureBank services, each in its OWN persistent PowerShell window, and waits for /actuator/health.

.DESCRIPTION
  * Reads scripts\set-env.local.ps1 (git-ignored) and checks that every required variable is set. Only variable
    NAMES are ever printed, never values.
  * Starts: customer (8081), account (8082), ledger (8083), transaction (8084), gateway (8080), in that order.
    A service whose port is already listening is skipped, so running this twice does not start duplicates.
  * Each service runs in a window that stays open (-NoExit) even if the service crashes, so the error stays visible.
    Close the window (or press Ctrl+C in it) to stop that service.
  * Does not start MySQL or Keycloak: start MySQL80 as a Windows service and Keycloak with identity\scripts\start-keycloak.ps1.
  * Applies ExecutionPolicy Bypass to the new windows only; nothing machine-wide is changed.

.PARAMETER Service     Subset to start, e.g. -Service account,ledger
.PARAMETER Maven       Path to mvn.cmd or mvnw.cmd. Default search: services\customer-service\mvnw.cmd, mvn on PATH, NetBeans' bundled Maven.
.PARAMETER CheckOnly   Only verify variables, Maven, MySQL, Keycloak and ports; start nothing.
.PARAMETER WaitSeconds Per-service health wait. Default 180.

.EXAMPLE
  .\scripts\start-services.ps1 -CheckOnly
  .\scripts\start-services.ps1
#>
[CmdletBinding()]
param(
    [ValidateSet('customer', 'account', 'ledger', 'transaction', 'gateway')]
    [string[]] $Service = @('customer', 'account', 'ledger', 'transaction', 'gateway'),
    [string] $EnvScript,
    [string] $Maven,
    [switch] $CheckOnly,
    [int] $WaitSeconds = 180
)

$ErrorActionPreference = 'Stop'
$scriptsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $scriptsDir
if (-not $EnvScript) { $EnvScript = Join-Path $scriptsDir 'set-env.local.ps1' }
if (-not (Test-Path -LiteralPath $EnvScript)) {
    throw "Missing $EnvScript. Copy scripts\set-env.example.ps1 to set-env.local.ps1 and fill it in."
}

# Load the local settings into THIS process (needed for the checks). Values are never printed.
. $EnvScript

$catalog = [ordered]@{
    customer    = @{ Port = 8081; Module = 'customer-service'; Required = @(
            'CUSTOMER_DB_USERNAME', 'CUSTOMER_DB_PASSWORD', 'CUSTOMER_DB_MIGRATION_USERNAME',
            'CUSTOMER_DB_MIGRATION_PASSWORD', 'CUSTOMER_JWT_ISSUER_URI') }
    account     = @{ Port = 8082; Module = 'account-service'; Required = @(
            'ACCOUNT_DB_URL', 'ACCOUNT_DB_USERNAME', 'ACCOUNT_DB_PASSWORD', 'ACCOUNT_DB_MIGRATION_USERNAME',
            'ACCOUNT_DB_MIGRATION_PASSWORD', 'ACCOUNT_JWT_ISSUER_URI', 'ACCOUNT_SERVICE_AUTH_CLIENT_SECRET') }
    ledger      = @{ Port = 8083; Module = 'ledger-service'; Required = @(
            'LEDGER_DB_URL', 'LEDGER_DB_USERNAME', 'LEDGER_DB_PASSWORD', 'LEDGER_DB_MIGRATION_USERNAME',
            'LEDGER_DB_MIGRATION_PASSWORD', 'LEDGER_JWT_ISSUER_URI') }
    transaction = @{ Port = 8084; Module = 'transaction-service'; Required = @(
            'TRANSACTION_DB_USERNAME', 'TRANSACTION_DB_PASSWORD', 'TRANSACTION_DB_MIGRATION_USERNAME',
            'TRANSACTION_DB_MIGRATION_PASSWORD', 'SECUREBANK_JWT_ISSUER_URI') }
    gateway     = @{ Port = 8080; Module = 'api-gateway'; Required = @('SECUREBANK_JWT_ISSUER_URI') }
}
$order = @($catalog.Keys | Where-Object { $Service -contains $_ })

function Get-EnvState([string] $name) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($value)) { return 'MISSING' }
    if ($value -match 'CHANGE_ME|^<.*>$') { return 'PLACEHOLDER' }
    return 'ok'
}

function Test-Listening([int] $port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

function Test-Health([int] $port) {
    try {
        $r = Invoke-RestMethod -Uri "http://localhost:$port/actuator/health" -TimeoutSec 3
        return ($r.status -eq 'UP')
    } catch {
        return $false
    }
}

function Resolve-Maven([string] $explicit) {
    $candidates = @()
    if ($explicit) { $candidates += $explicit }
    $candidates += (Join-Path $root 'services\customer-service\mvnw.cmd')
    $onPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    $candidates += (Join-Path $env:USERPROFILE 'Downloads\netbeans-28-bin\netbeans\java\maven\bin\mvn.cmd')
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath $c)) { return (Resolve-Path -LiteralPath $c).Path }
    }
    throw 'No Maven found. Pass -Maven <path to mvn.cmd or mvnw.cmd>.'
}

function ConvertTo-SingleQuoted([string] $text) {
    return "'" + $text.Replace("'", "''") + "'"
}

# ---- 1. environment variables (names only) ---------------------------------------------------------------
$envProblems = foreach ($name in $order) {
    foreach ($variable in $catalog[$name].Required) {
        $state = Get-EnvState $variable
        if ($state -ne 'ok') {
            [pscustomobject]@{ Service = $name; Variable = $variable; State = $state }
        }
    }
}
if ($order -contains 'transaction') {
    $mode = [Environment]::GetEnvironmentVariable('SERVICE_AUTH_MODE')
    if ($mode -ne 'OAUTH2_CLIENT_CREDENTIALS') {
        Write-Warning ("SERVICE_AUTH_MODE is not OAUTH2_CLIENT_CREDENTIALS: Transaction Service will call Account and " +
            "Ledger without a service token and their /internal endpoints will answer 401.")
    } else {
        $authProblems = foreach ($variable in 'SERVICE_AUTH_CLIENT_SECRET') {
            $state = Get-EnvState $variable
            if ($state -ne 'ok') { [pscustomobject]@{ Service = 'transaction'; Variable = $variable; State = $state } }
        }
        $envProblems = @($envProblems) + @($authProblems)
        if ((Get-EnvState 'SERVICE_AUTH_ISSUER_URI') -ne 'ok' -and (Get-EnvState 'SERVICE_AUTH_TOKEN_URI') -ne 'ok') {
            $envProblems = @($envProblems) + @([pscustomobject]@{
                    Service = 'transaction'; Variable = 'SERVICE_AUTH_ISSUER_URI (or SERVICE_AUTH_TOKEN_URI)'; State = 'MISSING' })
        }
    }
}
$envProblems = @($envProblems | Where-Object { $_ })
if ($envProblems.Count -gt 0) {
    Write-Host 'Environment variables that need attention (names only):' -ForegroundColor Yellow
    $envProblems | Format-Table -AutoSize
    throw "Fix $EnvScript and run again."
}
Write-Host 'Environment variables: all required names are set.' -ForegroundColor Green

# ---- 2. tools and dependencies ---------------------------------------------------------------------------
$mvnPath = Resolve-Maven $Maven
Write-Host "Maven: $mvnPath"
$mysql = Get-Service -Name 'MySQL80' -ErrorAction SilentlyContinue
if ($mysql -and $mysql.Status -eq 'Running') { Write-Host 'MySQL80: running' -ForegroundColor Green }
else { Write-Warning 'Windows service MySQL80 was not found running; the services need MySQL.' }
if (Test-Listening 8180) { Write-Host 'Keycloak: listening on 8180' -ForegroundColor Green }
else { Write-Warning 'Nothing is listening on 8180 (Keycloak). Start it with identity\scripts\start-keycloak.ps1 first.' }

if ($CheckOnly) {
    $portRows = foreach ($name in $order) {
        [pscustomobject]@{ Service = $name; Port = $catalog[$name].Port; AlreadyListening = (Test-Listening $catalog[$name].Port) }
    }
    $portRows | Format-Table -AutoSize
    Write-Host 'Check only: nothing was started.'
    return
}

# ---- 3. start each service in its own persistent window ---------------------------------------------------
function Start-ServiceWindow([string] $name) {
    $svc = $catalog[$name]
    $pom = Join-Path $root ('services\' + $svc.Module + '\pom.xml')
    $qRoot = ConvertTo-SingleQuoted $root
    $qEnv = ConvertTo-SingleQuoted $EnvScript
    $qMvn = ConvertTo-SingleQuoted $mvnPath
    $qPom = ConvertTo-SingleQuoted $pom
    $title = ConvertTo-SingleQuoted ("SecureBank $name (port " + $svc.Port + ')')
    $inner = @"
`$Host.UI.RawUI.WindowTitle = $title
Set-Location -LiteralPath $qRoot
. $qEnv
& $qMvn -f $qPom -B -ntp spring-boot:run
Write-Host ''
Write-Host 'The service process has ended. Read the messages above; this window stays open.' -ForegroundColor Yellow
"@
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($inner))
    Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoExit', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded) | Out-Null
}

$results = foreach ($name in $order) {
    $port = $catalog[$name].Port
    if (Test-Listening $port) {
        Write-Host "$name already listening on $port; not starting another copy." -ForegroundColor Yellow
        [pscustomobject]@{ Service = $name; Port = $port; Result = 'already running'; Healthy = (Test-Health $port) }
        continue
    }
    Write-Host "Starting $name (port $port) ..."
    Start-ServiceWindow $name
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        if (Test-Health $port) { $healthy = $true; break }
    }
    if (-not $healthy) { Write-Warning "$name did not report UP within $WaitSeconds s. Read its window for the error." }
    [pscustomobject]@{ Service = $name; Port = $port; Result = 'started'; Healthy = $healthy }
}
$results | Format-Table -AutoSize
Write-Host 'Run .\scripts\check-health.ps1 at any time to re-check. Keep the service windows open.'
