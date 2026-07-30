[CmdletBinding()]
param(
    [string]$EvidenceRoot = ".work/evidence"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$resolvedEvidenceRoot = if ([System.IO.Path]::IsPathRooted($EvidenceRoot)) {
    $EvidenceRoot
} else {
    Join-Path $repositoryRoot $EvidenceRoot
}

if (-not (Test-Path $resolvedEvidenceRoot -PathType Container)) {
    Write-Host "No measurement evidence directory requires finalization."
    return
}

$manifests = @(
    Get-ChildItem `
        -Path $resolvedEvidenceRoot `
        -Filter "measurement-series-run-manifest.json" `
        -File `
        -Recurse `
        -ErrorAction SilentlyContinue
)

$updatedCount = 0
foreach ($manifestFile in $manifests) {
    try {
        $manifest = Get-Content -LiteralPath $manifestFile.FullName -Raw -Encoding utf8 |
            ConvertFrom-Json -Depth 20
    } catch {
        Write-Warning "Unable to parse one measurement series manifest for finalization."
        continue
    }

    if ([string]$manifest.status -cne "running") {
        continue
    }

    $manifest.status = "interrupted"
    if ([string]::IsNullOrWhiteSpace([string]$manifest.completedAtUtc)) {
        $manifest.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    }
    $manifest.failureCode = "measurement-series-interrupted"
    $manifest.failureType = $null
    $manifest.failureCommand = $null
    $manifest.failureLine = $null

    $stagePath = Join-Path $manifestFile.DirectoryName ".measurement-series-run-manifest.tmp"
    try {
        $manifest |
            ConvertTo-Json -Depth 20 |
            Set-Content -LiteralPath $stagePath -Encoding utf8
        Move-Item -LiteralPath $stagePath -Destination $manifestFile.FullName -Force
        $updatedCount += 1
    } finally {
        Remove-Item -LiteralPath $stagePath -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "Interrupted measurement series manifests finalized: $updatedCount"
