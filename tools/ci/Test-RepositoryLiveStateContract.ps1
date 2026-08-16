[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$liveStateScript = Join-Path $RepositoryRoot "tools\ci\Get-RepositoryLiveState.ps1"
if (-not (Test-Path -LiteralPath $liveStateScript -PathType Leaf)) {
    throw "Repository live-state reader is missing: tools/ci/Get-RepositoryLiveState.ps1"
}

$temporaryRoot = Join-Path $RepositoryRoot ".work\contract-tests\repository-live-state"
$resolvedWorkRoot = [System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot ".work"))
$resolvedTemporaryRoot = [System.IO.Path]::GetFullPath($temporaryRoot)
if (-not $resolvedTemporaryRoot.StartsWith($resolvedWorkRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Temporary live-state contract path escaped .work."
}

if (Test-Path -LiteralPath $resolvedTemporaryRoot) {
    Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $resolvedTemporaryRoot -Force | Out-Null

function Invoke-Git {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = (& git -C $resolvedTemporaryRoot @Arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed.`n$output"
    }
    return $output
}

try {
    $null = Invoke-Git @("init", "--quiet", "-b", "main")
    $null = Invoke-Git @("config", "user.name", "MuxTV Contract Test")
    $null = Invoke-Git @("config", "user.email", "contract-test@muxtv.invalid")

    Set-Content -LiteralPath (Join-Path $resolvedTemporaryRoot "seed.txt") -Value "seed" -Encoding utf8
    $null = Invoke-Git @("add", "seed.txt")
    $null = Invoke-Git @("commit", "--quiet", "-m", "seed snapshot")
    $snapshotCommit = (Invoke-Git @("rev-parse", "HEAD")).Trim()

    $statusDirectory = Join-Path $resolvedTemporaryRoot ".work\meta"
    New-Item -ItemType Directory -Path $statusDirectory -Force | Out-Null
    @"
version: 2
truth_snapshot:
  reviewed_main_commit: $snapshotCommit
"@ | Set-Content -LiteralPath (Join-Path $statusDirectory "status.yaml") -Encoding utf8
    $null = Invoke-Git @("add", ".work/meta/status.yaml")
    $null = Invoke-Git @("commit", "--quiet", "-m", "record reviewed snapshot")
    $headCommit = (Invoke-Git @("rev-parse", "HEAD")).Trim()

    $json = (& $liveStateScript -RepositoryRoot $resolvedTemporaryRoot -AsJson | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Repository live-state reader failed for ancestor snapshot."
    }
    $state = $json | ConvertFrom-Json

    if ($state.head -cne $headCommit) {
        throw "Live state did not report exact HEAD. Expected=$headCommit Actual=$($state.head)"
    }
    if ($state.branch -cne "main") {
        throw "Live state did not report the current branch. Expected=main Actual=$($state.branch)"
    }
    if ($state.reviewedSnapshot -cne $snapshotCommit) {
        throw "Live state lost reviewed snapshot identity. Expected=$snapshotCommit Actual=$($state.reviewedSnapshot)"
    }
    if ($state.snapshotRelation -cne "ancestor") {
        throw "Expected ancestor snapshot relation, got $($state.snapshotRelation)."
    }
    if ([int]$state.commitsAheadOfSnapshot -ne 1) {
        throw "Expected one commit ahead of reviewed snapshot, got $($state.commitsAheadOfSnapshot)."
    }
    if ([bool]$state.dirty) {
        throw "Fresh live-state contract repository must be clean."
    }

    Set-Content -LiteralPath (Join-Path $resolvedTemporaryRoot "seed.txt") -Value "dirty" -Encoding utf8
    $dirtyJson = (& $liveStateScript -RepositoryRoot $resolvedTemporaryRoot -AsJson | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Repository live-state reader failed for dirty worktree."
    }
    $dirtyState = $dirtyJson | ConvertFrom-Json
    if (-not [bool]$dirtyState.dirty) {
        throw "Live state failed to report a dirty tracked worktree."
    }
    if ($dirtyState.head -cne $headCommit) {
        throw "Dirty worktree must not change reported HEAD."
    }
} finally {
    if (Test-Path -LiteralPath $resolvedTemporaryRoot) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
    }
}

Write-Host "Repository live-state contract passed."
