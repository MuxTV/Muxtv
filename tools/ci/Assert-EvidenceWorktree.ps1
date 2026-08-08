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
    if ($exitCode -eq 0) {
        return
    }
    if ($exitCode -eq 1) {
        throw $DirtyMessage
    }

    $details = ($output | Out-String).Trim()
    $suffix = if ($details) { " $details" } else { "" }
    throw "Unable to inspect tracked worktree provenance:${suffix}"
}

Invoke-TrackedDiffCheck `
    -Arguments @("diff", "--quiet", "--no-ext-diff", "--") `
    -DirtyMessage "Tracked worktree provenance mismatch: unstaged tracked changes detected."

Invoke-TrackedDiffCheck `
    -Arguments @("diff", "--cached", "--quiet", "--no-ext-diff", "--") `
    -DirtyMessage "Tracked worktree provenance mismatch: staged tracked changes detected."

Write-Host "Tracked evidence worktree provenance verified."
