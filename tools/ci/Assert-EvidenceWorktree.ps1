[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$RepositoryRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$LASTEXITCODE = 0
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

try {
    $resolvedRepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot -ErrorAction Stop).Path
} catch {
    throw "Unable to inspect tracked worktree provenance: repository root could not be resolved."
}

function Invoke-TrackedDiffCheck {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$DirtyMessage
    )

    $output = @(& git -C $resolvedRepositoryRoot @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Unable to inspect tracked worktree provenance."
    }
    if ($output.Count -gt 0) {
        throw $DirtyMessage
    }
}

Invoke-TrackedDiffCheck `
    -Arguments @("diff", "--name-only", "--no-ext-diff", "--") `
    -DirtyMessage "Tracked worktree provenance mismatch: unstaged tracked changes detected."

Invoke-TrackedDiffCheck `
    -Arguments @("diff", "--cached", "--name-only", "--no-ext-diff", "--") `
    -DirtyMessage "Tracked worktree provenance mismatch: staged tracked changes detected."

Write-Host "Tracked evidence worktree provenance verified."
