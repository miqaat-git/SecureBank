# =====================================================================
#  SECUREBANK MASTER STARTUP SCRIPT
#  This script guarantees the backend starts from Downloads and the
#  frontend starts from Documents, fixing all path-related errors.
# =====================================================================

$ErrorActionPreference = 'Continue'

# --- THE GOLDEN RULE: PATHS ---
$backend  = "C:\Users\admin\Downloads\securebank-final-backend-source-v2\securebank"
$frontend = "C:\Users\admin\Documents\securebank\frontend"

Write-Host "`n==============================================" -ForegroundColor Cyan
Write-Host "  STARTING SECUREBANK MASTER STACK" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

# --- 1. CLEANUP ZOMBIE PROCESSES ---
Write-Host "`n[1/5] Cleaning up old processes..." -ForegroundColor Yellow
foreach ($port in @(8080, 8081, 8082, 8083, 8084, 8180, 3000)) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conn) { 
        $ownerPid = $conn[0].OwningProcess
        Stop-Process -Id $ownerPid -Force -ErrorAction SilentlyContinue 
    }
}
Start-Sleep -Seconds 3
Write-Host "  Ports cleared." -ForegroundColor Gray

# --- 2. START KEYCLOAK ---
Write-Host "`n[2/5] Starting Keycloak on port 8180..." -ForegroundColor Yellow
$kcBat = "$backend\identity\.keycloak\keycloak-26.4.0\bin\kc.bat"
$envFile = "$backend\identity\scripts\set-keycloak-env.local.ps1"

if (-not (Test-Path $kcBat)) {
    Write-Host "  [ERROR] Keycloak not found at $kcBat" -ForegroundColor Red
    return
}

$kcCmd = "Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force; . '$envFile'; & '$kcBat' start-dev --http-port=8180 --hostname=http://localhost:8180 --import-realm"
Start-Process powershell -ArgumentList @('-NoExit', '-Command', $kcCmd) -WindowStyle Minimized

Write-Host "  Waiting for Keycloak to boot (this takes ~30s)..." -ForegroundColor Gray
$kcReady = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    if (Get-NetTCPConnection -LocalPort 8180 -State Listen -ErrorAction SilentlyContinue) {
        $kcReady = $true; break
    }
}
if ($kcReady) { Write-Host "  [OK] Keycloak is UP." -ForegroundColor Green } 
else { Write-Host "  [ERROR] Keycloak failed to start!" -ForegroundColor Red; return }

# --- 3. START BACKEND MICROSERVICES ---
Write-Host "`n[3/5] Starting 5 Backend Microservices..." -ForegroundColor Yellow
$services = @(
    @{ Name='Gateway';     Jar="$backend\services\api-gateway\target\api-gateway-1.0.0.jar"; Extra="" }
    @{ Name='Customer';    Jar="$backend\services\customer-service\target\customer-service-0.0.1-SNAPSHOT.jar"; Extra="" }
    @{ Name='Account';     Jar="$backend\services\account-service\target\account-service-1.0.0-SNAPSHOT.jar"; Extra="--spring.profiles.active=local" }
    @{ Name='Ledger';      Jar="$backend\services\ledger-service\target\ledger-service-0.1.0.jar"; Extra="" }
    @{ Name='Transaction'; Jar="$backend\services\transaction-service\target\transaction-service-1.0.0-SNAPSHOT.jar"; Extra="" }
)

foreach ($s in $services) {
    # We explicitly load the env files from Downloads so MySQL passwords match!
    $svcCmd = "Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force; Set-Location '$backend'; . '$backend\identity\scripts\set-keycloak-env.local.ps1'; . '$backend\scripts\set-env.local.ps1'; . '$backend\scripts\set-env-keycloak.local.ps1'; java -jar '$($s.Jar)' $($s.Extra)"
    Start-Process powershell -ArgumentList @('-NoExit', '-Command', $svcCmd) -WindowStyle Minimized
    Write-Host "  Launched $($s.Name) Service..." -ForegroundColor Gray
    Start-Sleep -Milliseconds 500
}

Write-Host "  Waiting 45s for microservices to initialize..." -ForegroundColor Gray
Start-Sleep -Seconds 45

# --- 4. START FRONTEND ---
Write-Host "`n[4/5] Starting React Frontend on port 3000..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList @('-NoExit', '-Command', "Set-Location '$frontend'; npm run dev") -WindowStyle Normal

Write-Host "  Waiting 15s for Vite to bundle..." -ForegroundColor Gray
Start-Sleep -Seconds 15

# --- 5. FINAL HEALTH CHECK ---
Write-Host "`n[5/5] FINAL HEALTH CHECK" -ForegroundColor Cyan
$allUp = $true
foreach ($port in @(8180, 8080, 8081, 8082, 8083, 8084, 3000)) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    $status = if ($conn) { "UP" } else { "DOWN" }
    $color  = if ($conn) { 'Green' } else { 'Red' }
    if (-not $conn) { $allUp = $false }
    Write-Host ("  Port {0,-4} : {1}" -f $port, $status) -ForegroundColor $color
}

Write-Host "`n==============================================" -ForegroundColor Cyan
if ($allUp) {
    Write-Host "  🎉 ALL SERVICES ARE UP AND RUNNING!" -ForegroundColor Green
    Write-Host "  Open your browser to: http://localhost:3000" -ForegroundColor Yellow
} else {
    Write-Host "  ⚠️ SOME SERVICES ARE DOWN. Check the minimized windows." -ForegroundColor Red
}
Write-Host "==============================================" -ForegroundColor Cyan