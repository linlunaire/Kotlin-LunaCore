#requires -Version 7.0
[CmdletBinding()]
param(
    [string] $MtrPath,
    [switch] $VerifyOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$transitCoreRoot = Split-Path -Parent $PSScriptRoot
$archiveRoot = Join-Path $transitCoreRoot 'legacy/mappings'
$payloadRoot = Join-Path $archiveRoot '1.21.1'
$manifest = Get-Content -LiteralPath (Join-Path $archiveRoot '1.21.1.sha256')
if ($manifest.Count -ne 31 -or @(Get-ChildItem -LiteralPath $payloadRoot -File).Count -ne 31) {
    throw 'The legacy snapshot must contain exactly 31 payload files.'
}
$seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($entry in $manifest) {
    if ($entry -notmatch '^([a-f0-9]{64})  ([A-Za-z0-9_.]+)$') {
        throw "Invalid legacy manifest entry: $entry"
    }
    $expectedHash = $Matches[1]
    $filename = $Matches[2]
    if (-not $seen.Add($filename)) { throw "Duplicate snapshot entry: $filename" }
    $actualHash = (Get-FileHash -LiteralPath (Join-Path $payloadRoot $filename) -Algorithm SHA256).Hash
    if ($actualHash -ne $expectedHash) { throw "Legacy snapshot checksum mismatch: $filename" }
}
Write-Host 'Verified all 31 original legacy snapshot files (SHA-256).'
if ($VerifyOnly) { return }
if ([string]::IsNullOrWhiteSpace($MtrPath)) { throw 'Specify -MtrPath for a clean 1.21.1-3.3.2 checkout, or -VerifyOnly.' }

$mtrRoot = (Resolve-Path -LiteralPath $MtrPath).Path
$gitRoot = & git -C $mtrRoot rev-parse --show-toplevel
if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($gitRoot) -ne [IO.Path]::GetFullPath($mtrRoot)) {
    throw '-MtrPath must be the MTR Git checkout root.'
}
$commit = & git -C $mtrRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $commit -ne '52095c771f8ab36527a723bb922fc6d8650bd4b5') {
    throw 'This migration is intentionally limited to the original 1.21.1-3.3.2 commit.'
}

$patch = Join-Path $archiveRoot 'mtr-1.21.1-independent.patch'
& git -C $mtrRoot apply --reverse --check $patch 2>$null
$alreadyApplied = $LASTEXITCODE -eq 0
if (-not $alreadyApplied) {
    & git -C $mtrRoot apply --check $patch
    if ($LASTEXITCODE -ne 0) { throw 'The maintenance patch does not apply; no files were changed.' }
}

# Check destinations before patching: never overwrite a different user file.
$vendorRoot = Join-Path $mtrRoot 'resources/vendor/fabric'
$copies = @{
    'fabric.min.js' = Join-Path $payloadRoot 'fabric.min.js'
    'LICENSE' = Join-Path $archiveRoot 'LICENSE-FABRIC.txt'
}
foreach ($filename in $copies.Keys) {
    $destination = Join-Path $vendorRoot $filename
    if ((Test-Path -LiteralPath $destination) -and
        (Get-FileHash -LiteralPath $destination).Hash -ne (Get-FileHash -LiteralPath $copies[$filename]).Hash) {
        throw "Refusing to overwrite a different file: $destination"
    }
}

if (-not $alreadyApplied) {
    & git -C $mtrRoot apply $patch
    if ($LASTEXITCODE -ne 0) { throw 'Could not apply the checked maintenance patch.' }
}
New-Item -ItemType Directory -Path $vendorRoot -Force | Out-Null
foreach ($filename in $copies.Keys) {
    Copy-Item -LiteralPath $copies[$filename] -Destination (Join-Path $vendorRoot $filename)
}

$buildScript = Get-Content -LiteralPath (Join-Path $mtrRoot 'build.gradle') -Raw
if ($buildScript -match 'https://[^\s"'']*Minecraft-Mappings') {
    throw 'The patched build still references a Minecraft-Mappings download.'
}
Write-Host 'Prepared the 1.21.1 maintenance checkout. No tag, commit, remote or runtime mod was changed.'
Write-Host 'Build MTR with its existing Java 21 / Gradle 8.14.5 wrapper, then build the adjacent ANTE 1.21.1 checkout.'
