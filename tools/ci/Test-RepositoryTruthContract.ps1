[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Read-RequiredFile {
    param([string]$RelativePath)

    $path = Join-Path $RepositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Repository truth file is missing: $RelativePath"
    }
    Get-Content -LiteralPath $path -Raw
}

function Get-SingleMatch {
    param(
        [string]$Content,
        [string]$Pattern,
        [string]$Description
    )

    $matches = [regex]::Matches($Content, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if ($matches.Count -ne 1) {
        throw "Expected exactly one $Description, found $($matches.Count)."
    }
    $matches[0].Groups[1].Value
}

$settings = Read-RequiredFile "settings.gradle.kts"
$modules = Read-RequiredFile ".work\meta\modules.yaml"
$documents = Read-RequiredFile ".work\meta\documents.yaml"
$status = Read-RequiredFile ".work\meta\status.yaml"
$currentState = Read-RequiredFile ".work\CURRENT-STATE.md"

$settingsModules = @(
    [regex]::Matches($settings, '"(:[^"\r\n]+)"') |
        ForEach-Object { $_.Groups[1].Value } |
        Sort-Object -Unique
)
$metadataModules = @(
    [regex]::Matches($modules, '^\s+gradle_path:\s+([^\s#]+)\s*$', [System.Text.RegularExpressions.RegexOptions]::Multiline) |
        ForEach-Object { $_.Groups[1].Value } |
        Sort-Object -Unique
)
if ([string]::Join("`n", $settingsModules) -cne [string]::Join("`n", $metadataModules)) {
    throw "settings.gradle.kts modules do not match .work/meta/modules.yaml actual_modules."
}

$actualModuleNames = @($metadataModules | ForEach-Object { $_.TrimStart(":").Replace(":", "_").Replace("-", "_") })
$plannedSection = [regex]::Match($modules, '(?ms)^planned_not_created:\s*$(.*?)(?=^rules:\s*$)')
if (-not $plannedSection.Success) {
    throw "modules.yaml is missing planned_not_created and rules sections."
}
$plannedModuleNames = @(
    [regex]::Matches($plannedSection.Groups[1].Value, '^\s+-\s+([^\s#]+)\s*$', [System.Text.RegularExpressions.RegexOptions]::Multiline) |
        ForEach-Object { $_.Groups[1].Value.Replace("-", "_") }
)
$overlap = @($plannedModuleNames | Where-Object { $actualModuleNames -ccontains $_ })
if ($overlap.Count -ne 0) {
    throw "Implemented modules are still listed as planned_not_created: $($overlap -join ', ')."
}

$architectureFiles = @(
    ".work\ARCHITECTURE.md",
    ".work\CURRENT-STATE.md",
    ".work\meta\architecture.yaml",
    ".work\meta\status.yaml"
)
$architectureVersions = foreach ($relativePath in $architectureFiles) {
    $content = Read-RequiredFile $relativePath
    Get-SingleMatch $content '^\s*architecture_version:\s*(\d+)\s*$' "architecture_version in $relativePath"
}
if (@($architectureVersions | Sort-Object -Unique).Count -ne 1 -or $architectureVersions[0] -ne "2") {
    throw "Architecture version must be normative v2 in all repository truth surfaces."
}

$planPath = Get-SingleMatch $documents '^\s*current_execution:\s+([^\s#]+)\s*$' "canonical current_execution plan"
$null = Read-RequiredFile $planPath

$statusCommit = Get-SingleMatch $status '^implementation_source_commit:\s*([0-9a-f]{40})\s*$' "status implementation_source_commit"
$currentCommit = Get-SingleMatch $currentState '^implementation_source_commit:\s*([0-9a-f]{40})\s*$' "current-state implementation_source_commit"
if ($statusCommit -cne $currentCommit) {
    throw "CURRENT-STATE and status.yaml disagree on implementation_source_commit."
}

& git -C $RepositoryRoot cat-file -e "$statusCommit^{commit}" 2>$null
$acceptedCommitExists = $LASTEXITCODE -eq 0
$acceptedCommitIsAncestor = $false
if ($acceptedCommitExists) {
    & git -C $RepositoryRoot merge-base --is-ancestor $statusCommit HEAD
    $acceptedCommitIsAncestor = $LASTEXITCODE -eq 0
}

$isShallow = ((& git -C $RepositoryRoot rev-parse --is-shallow-repository).Trim() -ceq "true")
if ($isShallow -and (-not $acceptedCommitExists -or -not $acceptedCommitIsAncestor)) {
    & git -C $RepositoryRoot fetch --quiet --no-tags --unshallow origin $statusCommit
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to recover repository history for accepted implementation source commit: $statusCommit"
    }
    & git -C $RepositoryRoot cat-file -e "$statusCommit^{commit}" 2>$null
    $acceptedCommitExists = $LASTEXITCODE -eq 0
    if ($acceptedCommitExists) {
        & git -C $RepositoryRoot merge-base --is-ancestor $statusCommit HEAD
        $acceptedCommitIsAncestor = $LASTEXITCODE -eq 0
    }
}

if (-not $acceptedCommitExists) {
    throw "Accepted implementation source commit does not exist locally: $statusCommit"
}
if (-not $acceptedCommitIsAncestor) {
    throw "Accepted implementation source commit is not an ancestor of HEAD: $statusCommit"
}

$currentTruthFiles = @(
    "README.md",
    ".work\CURRENT-STATE.md",
    ".work\ROADMAP.md",
    ".work\architecture\module-map.md",
    ".work\meta\modules.yaml",
    ".work\meta\status.yaml",
    $planPath
)
$staleBaseline = "5bb6ee1f754785b2b236d6dcb52fd4458780e758"
$staleFiles = @($currentTruthFiles | Where-Object { (Read-RequiredFile $_).Contains($staleBaseline) })
if ($staleFiles.Count -ne 0) {
    throw "Current repository truth still contains stale baseline $staleBaseline in: $($staleFiles -join ', ')."
}

& (Join-Path $PSScriptRoot "Test-RepositoryLiveStateContract.ps1") -RepositoryRoot $RepositoryRoot

Write-Host "Repository truth contract passed for $($settingsModules.Count) Gradle modules at accepted $statusCommit."
