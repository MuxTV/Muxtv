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

function Publish-FinalizedManifest {
    param(
        [Parameter(Mandatory)][System.IO.FileInfo]$ManifestFile,
        [Parameter(Mandatory)]$Manifest,
        [Parameter(Mandatory)][string]$StageFileName
    )

    $stagePath = Join-Path $ManifestFile.DirectoryName $StageFileName
    try {
        $Manifest |
            ConvertTo-Json -Depth 20 |
            Set-Content -LiteralPath $stagePath -Encoding utf8
        Move-Item -LiteralPath $stagePath -Destination $ManifestFile.FullName -Force
    } finally {
        Remove-Item -LiteralPath $stagePath -Force -ErrorAction SilentlyContinue
    }
}

$updatedCount = 0
$generalManifests = @(
    Get-ChildItem `
        -Path $resolvedEvidenceRoot `
        -Filter "measurement-series-run-manifest.json" `
        -File `
        -Recurse `
        -ErrorAction SilentlyContinue
)
foreach ($manifestFile in $generalManifests) {
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

    Publish-FinalizedManifest `
        -ManifestFile $manifestFile `
        -Manifest $manifest `
        -StageFileName ".measurement-series-run-manifest.tmp"
    $updatedCount += 1
}

$focusedManifests = @(
    Get-ChildItem `
        -Path $resolvedEvidenceRoot `
        -Filter "m3u-series-run-manifest.json" `
        -File `
        -Recurse `
        -ErrorAction SilentlyContinue
)
foreach ($manifestFile in $focusedManifests) {
    try {
        $manifest = Get-Content -LiteralPath $manifestFile.FullName -Raw -Encoding utf8 |
            ConvertFrom-Json -Depth 20
    } catch {
        Write-Warning "Unable to parse one focused M3U series manifest for finalization."
        continue
    }

    if ([string]$manifest.status -cne "running") {
        continue
    }

    $manifest.status = "interrupted"
    if ([string]::IsNullOrWhiteSpace([string]$manifest.completedAtUtc)) {
        $manifest.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    }

    Publish-FinalizedManifest `
        -ManifestFile $manifestFile `
        -Manifest $manifest `
        -StageFileName ".m3u-series-run-manifest.tmp"
    $updatedCount += 1
}

Write-Host "Interrupted measurement series manifests finalized: $updatedCount"
