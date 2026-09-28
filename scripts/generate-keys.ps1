# Regenerates the RS256 key pair used to sign/verify JWTs.
#
# The repo ships with a placeholder dev key pair so `mvn verify` and
# `docker compose up` work out of the box. Run this script whenever you
# want a fresh key pair (e.g. before any real deployment) -- never reuse
# the committed dev keys outside your own machine.
#
# Requires OpenSSL on PATH (ships with Git for Windows -- use "Git Bash" or
# add <Git install dir>\usr\bin to PATH if 'openssl' isn't recognized).

$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $PSScriptRoot
$AuthKeysDir = Join-Path $RootDir "auth/src/main/resources/keys"
$GatewayKeysDir = Join-Path $RootDir "gateway/src/main/resources/keys"
$InventoryKeysDir = Join-Path $RootDir "inventory/src/main/resources/keys"
$InventoryTestKeysDir = Join-Path $RootDir "inventory/src/test/resources/keys"
$OrderKeysDir = Join-Path $RootDir "order/src/main/resources/keys"
$OrderTestKeysDir = Join-Path $RootDir "order/src/test/resources/keys"

New-Item -ItemType Directory -Force -Path $AuthKeysDir | Out-Null
New-Item -ItemType Directory -Force -Path $GatewayKeysDir | Out-Null
New-Item -ItemType Directory -Force -Path $InventoryKeysDir | Out-Null
New-Item -ItemType Directory -Force -Path $InventoryTestKeysDir | Out-Null
New-Item -ItemType Directory -Force -Path $OrderKeysDir | Out-Null
New-Item -ItemType Directory -Force -Path $OrderTestKeysDir | Out-Null

$TmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $TmpDir | Out-Null

try {
    $privateKeyPath = Join-Path $TmpDir "private_key.pem"
    $publicKeyPath = Join-Path $TmpDir "public_key.pem"

    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out $privateKeyPath
    openssl rsa -pubout -in $privateKeyPath -out $publicKeyPath

    Copy-Item $privateKeyPath (Join-Path $AuthKeysDir "private_key.pem") -Force
    Copy-Item $publicKeyPath (Join-Path $AuthKeysDir "public_key.pem") -Force
    Copy-Item $publicKeyPath (Join-Path $GatewayKeysDir "public_key.pem") -Force
    Copy-Item $publicKeyPath (Join-Path $InventoryKeysDir "public_key.pem") -Force
    Copy-Item $publicKeyPath (Join-Path $OrderKeysDir "public_key.pem") -Force
    # Test-only keys let a service's tests mint fixture JWTs without running the
    # real auth service. Never copy the private key into a service's main resources.
    Copy-Item $privateKeyPath (Join-Path $InventoryTestKeysDir "private_key.pem") -Force
    Copy-Item $publicKeyPath (Join-Path $InventoryTestKeysDir "public_key.pem") -Force
    Copy-Item $privateKeyPath (Join-Path $OrderTestKeysDir "private_key.pem") -Force
    Copy-Item $publicKeyPath (Join-Path $OrderTestKeysDir "public_key.pem") -Force

    Write-Host "New RSA key pair written to:"
    Write-Host "  $AuthKeysDir\private_key.pem  (auth service only -- never share this)"
    Write-Host "  $AuthKeysDir\public_key.pem"
    Write-Host "  $GatewayKeysDir\public_key.pem"
    Write-Host "  $InventoryKeysDir\public_key.pem"
    Write-Host "  $OrderKeysDir\public_key.pem"
    Write-Host "  $InventoryTestKeysDir\*.pem (test fixtures only)"
    Write-Host "  $OrderTestKeysDir\*.pem (test fixtures only)"
}
finally {
    Remove-Item -Recurse -Force $TmpDir
}
