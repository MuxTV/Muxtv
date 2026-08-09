[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$workflowPath = Join-Path $repositoryRoot ".github\workflows\focused-m3u-evidence.yml"
$eligibilityContract = Join-Path $PSScriptRoot "Test-M3uSeriesEligibilityContract.ps1"
if (-not (Test-Path $workflowPath -PathType Leaf)) {
    throw "Accepted-main focused M3U evidence workflow is missing."
}
if (-not (Test-Path $eligibilityContract -PathType Leaf)) {
    throw "Focused M3U claim-eligibility contract is missing."
}

$content = Get-Content -LiteralPath $workflowPath -Raw -Encoding utf8

foreach ($requiredToken in @(
    'workflow_dispatch:',
    'push:',
    'branches:',
    '- main',
    'paths:',
    'runs-on: [self-hosted, Windows, X64, muxtv-android]',
    'cancel-in-progress: false',
    'ref: ${{ github.sha }}',
    'Assert-EvidenceCommit.ps1',
    'Test-MeasurementHarnessSyntax.ps1',
    'Invoke-M3uCorpusSeries.ps1',
    '-M3uProfile medium-10k',
    '-M3uProfile large-50k',
    '-M3uSeed 20260728',
    '-Repetitions 5',
    'Finalize-MeasurementSeriesEvidence.ps1',
    'actions/upload-artifact',
    'if: always()'
)) {
    if ($content -notmatch [regex]::Escape($requiredToken)) {
        throw "Accepted-main focused M3U workflow is missing required contract token: $requiredToken"
    }
}

if ($content -match '(?m)^\s*pull_request\s*:') {
    throw "Accepted-main focused M3U evidence workflow must not run expensive focused series on pull requests."
}
foreach ($forbiddenToken in @(
    'matrix:',
    'ForEach-Object -Parallel',
    'Start-Job',
    'Start-ThreadJob',
    'cancel-in-progress: true'
)) {
    if ($content -match [regex]::Escape($forbiddenToken)) {
        throw "Accepted-main focused M3U workflow contains forbidden parallel/cancelling behavior: $forbiddenToken"
    }
}

$mediumIndex = $content.IndexOf('-M3uProfile medium-10k', [System.StringComparison]::Ordinal)
$largeIndex = $content.IndexOf('-M3uProfile large-50k', [System.StringComparison]::Ordinal)
if ($mediumIndex -lt 0 -or $largeIndex -lt 0 -or $mediumIndex -gt $largeIndex) {
    throw "Accepted-main focused M3U workflow must run medium-10k before large-50k."
}

Write-Host "Accepted-main focused M3U evidence workflow contract is valid."
& $eligibilityContract
