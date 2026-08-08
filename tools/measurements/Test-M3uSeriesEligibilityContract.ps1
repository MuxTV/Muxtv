[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$m3uSeriesScript = Join-Path $PSScriptRoot "Invoke-M3uCorpusSeries.ps1"
$finalizerScript = Join-Path $PSScriptRoot "Finalize-MeasurementSeriesEvidence.ps1"
foreach ($path in @($m3uSeriesScript, $finalizerScript)) {
    if (-not (Test-Path $path -PathType Leaf)) {
        throw "Focused M3U eligibility contract dependency is missing: $([System.IO.Path]::GetFileName($path))"
    }
}

$seriesContent = Get-Content -LiteralPath $m3uSeriesScript -Raw -Encoding utf8
$finalizerContent = Get-Content -LiteralPath $finalizerScript -Raw -Encoding utf8

foreach ($requiredToken in @(
    'claimEligible = $false',
    '$manifest.claimEligible = ($Repetitions -ge 5)',
    '$manifest.status = "passed"',
    '$manifest.claimEligible = $false',
    '$manifest.status = "failed"'
)) {
    if ($seriesContent -notmatch [regex]::Escape($requiredToken)) {
        throw "Focused M3U series is missing terminal-aware claim-eligibility contract token: $requiredToken"
    }
}

$successEligibilityIndex = $seriesContent.IndexOf('$manifest.claimEligible = ($Repetitions -ge 5)', [System.StringComparison]::Ordinal)
$passedStatusIndex = $seriesContent.IndexOf('$manifest.status = "passed"', [System.StringComparison]::Ordinal)
if ($successEligibilityIndex -lt 0 -or
    $passedStatusIndex -lt 0 -or
    $successEligibilityIndex -gt $passedStatusIndex) {
    throw "Focused M3U claim eligibility must be granted only in the successful path before publishing status=passed."
}

$failedStatusIndex = $seriesContent.IndexOf('$manifest.status = "failed"', [System.StringComparison]::Ordinal)
if ($failedStatusIndex -lt 0) {
    throw "Focused M3U failed status publication contract is missing."
}
$failedEligibilityIndex = $seriesContent.IndexOf(
    '$manifest.claimEligible = $false',
    $failedStatusIndex,
    [System.StringComparison]::Ordinal
)
if ($failedEligibilityIndex -lt $failedStatusIndex) {
    throw "Focused M3U failed evidence must explicitly remain non-claim-eligible."
}

if ($finalizerContent -notmatch [regex]::Escape('$manifest.claimEligible = $false')) {
    throw "Focused M3U interrupted finalization must revoke claim eligibility."
}

Write-Host "Focused M3U terminal-aware claim-eligibility contract is valid."
