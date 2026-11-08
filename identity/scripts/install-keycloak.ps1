<#
 Downloads Keycloak 26.4.0 (pinned) from the official GitHub release and unpacks it to identity\.keycloak.
 Needs only Windows PowerShell 5.1+ and JDK 21 (java -version). No Docker. Verifies a pinned SHA-256 first.
 Usage (from the project root):  .\identity\scripts\install-keycloak.ps1
#>
$ErrorActionPreference = 'Stop'
$version = '26.4.0'
$sha256  = '1b6a11a2726ac8a8dc9c91d5fafe989a75ce4f1622d7f7b45c21d5bc16629c0a'
$url     = "https://github.com/keycloak/keycloak/releases/download/$version/keycloak-$version.zip"
$root    = Split-Path -Parent $PSScriptRoot
$target  = Join-Path $root '.keycloak'
$zip     = Join-Path $target "keycloak-$version.zip"

if (Test-Path (Join-Path $target "keycloak-$version\bin\kc.bat")) {
    Write-Host "Keycloak $version is already installed in $target"; return
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java not found. Install JDK 21 first.' }
New-Item -ItemType Directory -Force -Path $target | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Write-Host "Downloading $url (about 150 MB)..."
Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
$actual = (Get-FileHash -Algorithm SHA256 -Path $zip).Hash.ToLowerInvariant()
if ($actual -ne $sha256) { Remove-Item $zip -Force; throw "SHA-256 mismatch (got $actual). Download deleted." }
Expand-Archive -Path $zip -DestinationPath $target -Force
Remove-Item $zip -Force
Write-Host "Installed Keycloak $version in $target"
