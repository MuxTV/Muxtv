[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$workflowPath = Join-Path $repositoryRoot ".github\workflows\focused-m3u-evidence.yml"
$eligibilityContract = Join-Path $PSScriptRoot "Test-M3uSeriesEligibilityContract.ps1"
if (-not (Test-Path $workflowPath -PathType Leaf)) {
    throw "Manual hosted M3U stress evidence workflow is missing."
}
if (-not (Test-Path $eligibilityContract -PathType Leaf)) {
    throw "Focused M3U claim-eligibility contract is missing."
}

$content = Get-Content -LiteralPath $workflowPath -Raw -Encoding utf8

foreach ($requiredToken in @(
    'workflow_dispatch:',
    'runs-on: windows-latest',
    'uses: ./.github/actions/setup-muxtv-jdks',
    'cancel-in-progress: false',
    'ref: ${{ github.sha }}',
    'refs/heads/main',
    'Assert-EvidenceCommit.ps1',
    'Test-MeasurementHarnessSyntax.ps1',
    'runner_environment=github-hosted-ephemeral',
    'longitudinal_absolute_performance_claims=false',
    'Invoke-M3uCorpusSeries.ps1',
    '-M3uProfile medium-10k',
    '-M3uProfile large-50k',
    '-M3uSeed 20260728',
    '-Repetitions 4',
    '-RunnerLabel github-hosted-windows-x64-v1',
    'Finalize-MeasurementSeriesEvidence.ps1',
    'uses: ./.github/actions/upload-evidence-with-retry',
    'github-token: ${{ github.token }}',
    'if: always()'
)) {
    if ($content -notmatch [regex]::Escape($requiredToken)) {
        throw "Manual hosted M3U stress workflow is missing required contract token: $requiredToken"
    }
}

if ($content -match '(?m)^\s*pull_request\s*:') {
    throw "Manual hosted M3U stress workflow must not run expensive series on pull requests."
}
if ($content -match '(?m)^\s*push\s*:') {
    throw "Manual hosted M3U stress workflow must not run automatically on main pushes."
}
foreach ($forbiddenToken in @(
    'self-hosted',
    '-Repetitions 5',
    'matrix:',
    'ForEach-Object -Parallel',
    'Start-Job',
    'Start-ThreadJob',
    'cancel-in-progress: true'
)) {
    if ($content -match [regex]::Escape($forbiddenToken)) {
        throw "Manual hosted M3U stress workflow contains forbidden claim/parallel/runner behavior: $forbiddenToken"
    }
}
if ($content -match '(?mi)^\s*uses:\s*actions/upload-artifact@') {
    throw "Manual hosted M3U stress workflow must publish through the shared bounded evidence action, not upload-artifact directly."
}

$mediumIndex = $content.IndexOf('-M3uProfile medium-10k', [System.StringComparison]::Ordinal)
$largeIndex = $content.IndexOf('-M3uProfile large-50k', [System.StringComparison]::Ordinal)
if ($mediumIndex -lt 0 -or $largeIndex -lt 0 -or $mediumIndex -gt $largeIndex) {
    throw "Manual hosted M3U stress workflow must run medium-10k before large-50k."
}

Write-Host "Manual hosted M3U stress evidence workflow contract is valid and non-claiming."
& $eligibilityContract
