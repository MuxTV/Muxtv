[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path,
    [switch]$AsJson
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Invoke-GitRequired {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [string]$Description = "Git command"
    )

    $output = (& git -C $RepositoryRoot @Arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed: git $($Arguments -join ' ')`n$output"
    }
    return $output
}

function Get-SingleMatch {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Content,
        [Parameter(Mandatory = $true)]
        [string]$Pattern,
        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $matches = [regex]::Matches(
        $Content,
        $Pattern,
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    if ($matches.Count -ne 1) {
        throw "Expected exactly one $Description, found $($matches.Count)."
    }
    return $matches[0].Groups[1].Value
}

$resolvedRepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$RepositoryRoot = $resolvedRepositoryRoot
$statusPath = Join-Path $RepositoryRoot ".work\meta\status.yaml"
if (-not (Test-Path -LiteralPath $statusPath -PathType Leaf)) {
    throw "Repository status metadata is missing: .work/meta/status.yaml"
}

$status = Get-Content -LiteralPath $statusPath -Raw
$reviewedSnapshot = Get-SingleMatch `
    -Content $status `
    -Pattern '^\s+reviewed_main_commit:\s*([0-9a-f]{40})\s*$' `
    -Description "truth_snapshot.reviewed_main_commit"

$head = (Invoke-GitRequired -Arguments @("rev-parse", "HEAD") -Description "Resolve live HEAD").Trim()
if ($head -notmatch '^[0-9a-f]{40}$') {
    throw "Git returned an invalid HEAD SHA: $head"
}

$branchOutput = (Invoke-GitRequired -Arguments @("branch", "--show-current") -Description "Resolve live branch").Trim()
$branch = if ([string]::IsNullOrWhiteSpace($branchOutput)) { "DETACHED" } else { $branchOutput }

$statusOutput = Invoke-GitRequired -Arguments @("status", "--porcelain=v1") -Description "Inspect worktree state"
$dirty = -not [string]::IsNullOrWhiteSpace($statusOutput)

& git -C $RepositoryRoot cat-file -e "$reviewedSnapshot^{commit}" 2>$null
$snapshotExists = $LASTEXITCODE -eq 0

$snapshotRelation = "missing"
$commitsAheadOfSnapshot = $null

if ($snapshotExists) {
    if ($head -ceq $reviewedSnapshot) {
        $snapshotRelation = "exact"
        $commitsAheadOfSnapshot = 0
    } else {
        & git -C $RepositoryRoot merge-base --is-ancestor $reviewedSnapshot HEAD 2>$null
        $isAncestor = $LASTEXITCODE -eq 0
        if ($isAncestor) {
            $snapshotRelation = "ancestor"
            $aheadText = (Invoke-GitRequired `
                -Arguments @("rev-list", "--count", "$reviewedSnapshot..HEAD") `
                -Description "Count commits ahead of reviewed snapshot").Trim()
            $aheadCount = 0
            if (-not [int]::TryParse($aheadText, [ref]$aheadCount) -or $aheadCount -lt 0) {
                throw "Git returned an invalid ahead count for reviewed snapshot: $aheadText"
            }
            $commitsAheadOfSnapshot = $aheadCount
        } else {
            $snapshotRelation = "diverged"
        }
    }
}

$state = [ordered]@{
    head = $head
    branch = $branch
    dirty = $dirty
    reviewedSnapshot = $reviewedSnapshot
    snapshotRelation = $snapshotRelation
    commitsAheadOfSnapshot = $commitsAheadOfSnapshot
}

if ($AsJson) {
    $state | ConvertTo-Json -Depth 3
} else {
    [pscustomobject]$state
}
