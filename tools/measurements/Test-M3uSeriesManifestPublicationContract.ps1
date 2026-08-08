[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$m3uSeriesScript = Join-Path $PSScriptRoot "Invoke-M3uCorpusSeries.ps1"
if (-not (Test-Path $m3uSeriesScript -PathType Leaf)) {
    throw "Focused M3U series entry point was not found."
}

$content = Get-Content -LiteralPath $m3uSeriesScript -Raw -Encoding utf8

if ($content -match [regex]::Escape('Set-Content -Path $manifestPath') -or
    $content -match [regex]::Escape('Set-Content -LiteralPath $manifestPath')) {
    throw "Focused M3U manifest publication still writes directly to the live manifest path."
}

foreach ($requiredToken in @(
    'Join-Path $seriesDirectory',
    'm3u-series-run-manifest.stage',
    'Move-Item',
    '-Destination $manifestPath',
    'Remove-Item'
)) {
    if ($content -notmatch [regex]::Escape($requiredToken)) {
        throw "Focused M3U manifest publication is missing atomic stage/replace contract token: $requiredToken"
    }
}

$stageIndex = $content.IndexOf('m3u-series-run-manifest.stage', [System.StringComparison]::Ordinal)
$publishIndex = $content.IndexOf('-Destination $manifestPath', [System.StringComparison]::Ordinal)
if ($stageIndex -lt 0 -or $publishIndex -lt 0 -or $stageIndex -gt $publishIndex) {
    throw "Focused M3U manifest must stage a complete same-directory file before replacing the live manifest."
}

Write-Host "Focused M3U manifest atomic-publication contract is valid."
