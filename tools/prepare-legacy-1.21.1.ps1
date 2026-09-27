#requires -Version 7.0
[CmdletBinding()]
param(
    [string] $MtrPath,
    [switch] $VerifyOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# The immutable commit, not a movable tag name, selects both the payload and
# its original verifier/migration tool. No historical Java sources live on main.
$lunaCoreRoot = Split-Path -Parent $PSScriptRoot
$archiveCommit = '650800892192395a8755ef41efcfe90b913a3c4a'
& git -C $lunaCoreRoot cat-file -e "$archiveCommit^{commit}" 2>$null
if ($LASTEXITCODE -ne 0) {
    throw 'Missing legacy archive. Run: git fetch origin tag transit-core-0.1.0, then retry.'
}

$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$archiveWorkspace = Join-Path $tempRoot ('lunacore-legacy-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $archiveWorkspace | Out-Null
try {
    $archivePath = Join-Path $archiveWorkspace 'snapshot.zip'
    & git -C $lunaCoreRoot -c core.autocrlf=false archive --format=zip "--output=$archivePath" $archiveCommit -- legacy tools/prepare-legacy-1.21.1.ps1
    if ($LASTEXITCODE -ne 0) { throw 'Could not export the pinned legacy archive.' }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $archiveWorkspace
    $archivedTool = Join-Path $archiveWorkspace 'tools/prepare-legacy-1.21.1.ps1'
    & $archivedTool -MtrPath $MtrPath -VerifyOnly:$VerifyOnly
} finally {
    # Only remove the unique directory created above, never a caller-supplied path.
    $resolvedWorkspace = (Resolve-Path -LiteralPath $archiveWorkspace).Path
    $expectedWorkspace = [IO.Path]::GetFullPath($archiveWorkspace)
    if ($resolvedWorkspace -ne $expectedWorkspace -or
        -not [IO.Path]::GetDirectoryName($resolvedWorkspace).Equals(
            $tempRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar),
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove unexpected archive workspace: $resolvedWorkspace"
    }
    Remove-Item -LiteralPath $resolvedWorkspace -Recurse -Force
}
