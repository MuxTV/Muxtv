[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$finalizerScript = Join-Path $PSScriptRoot "Finalize-MeasurementSeriesEvidence.ps1"
if (-not (Test-Path $finalizerScript -PathType Leaf)) {
    throw "Measurement series finalizer was not found."
}

$testRoot = Join-Path $repositoryRoot ".work\evidence\m3u-finalizer-contract"
Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $testRoot | Out-Null

$canonicalSha = "a" * 64
$runningDirectory = Join-Path $testRoot "running"
$passedDirectory = Join-Path $testRoot "passed"
New-Item -ItemType Directory -Path $runningDirectory, $passedDirectory | Out-Null

$runningManifestPath = Join-Path $runningDirectory "m3u-series-run-manifest.json"
$passedManifestPath = Join-Path $passedDirectory "m3u-series-run-manifest.json"

$partialRuns = @(
    [ordered]@{
        repetitionId = "host-01"
        reportName = "m3u-medium-10k-01.json"
        corpusSha256 = $canonicalSha
        corpusUtf8ByteCount = 12345
    }
)
$corpus = [ordered]@{
    sha256 = $canonicalSha
    utf8ByteCount = 12345
    expectedParsedEntries = 9999
    expectedSkippedEntries = 1
    expectedWarningCount = 1
}

[ordered]@{
    schemaVersion = 1
    repository = "MuxTV/Muxtv"
    branch = "finalizer-contract"
    commit = "1" * 40
    profile = "medium-10k"
    seed = 20260728
    repetitions = 5
    warmups = 2
    iterations = 5
    runnerLabel = "self-hosted-windows-x64-v1"
    claimEligible = $true
    thresholdApplied = $false
    status = "running"
    startedAtUtc = "2026-08-08T00:00:00.0000000Z"
    completedAtUtc = $null
    corpus = $corpus
    runs = $partialRuns
    analysisOutput = $null
    failureType = $null
    failureLine = $null
} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $runningManifestPath -Encoding utf8

[ordered]@{
    schemaVersion = 1
    repository = "MuxTV/Muxtv"
    branch = "finalizer-contract"
    commit = "2" * 40
    profile = "large-50k"
    seed = 20260728
    repetitions = 5
    warmups = 2
    iterations = 5
    runnerLabel = "self-hosted-windows-x64-v1"
    claimEligible = $true
    thresholdApplied = $false
    status = "passed"
    startedAtUtc = "2026-08-08T00:00:00.0000000Z"
    completedAtUtc = "2026-08-08T00:01:00.0000000Z"
    corpus = $corpus
    runs = $partialRuns
    analysisOutput = "m3u-large-50k-variance.json"
    failureType = $null
    failureLine = $null
} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $passedManifestPath -Encoding utf8

try {
    & $finalizerScript -EvidenceRoot $testRoot

    $running = Get-Content -LiteralPath $runningManifestPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 20
    $passed = Get-Content -LiteralPath $passedManifestPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 20

    if ([string]$running.status -cne "interrupted") {
        throw "Focused M3U running manifest was not finalized as interrupted."
    }
    if ([string]::IsNullOrWhiteSpace([string]$running.completedAtUtc)) {
        throw "Focused M3U interrupted manifest is missing completedAtUtc."
    }
    if (@($running.runs).Count -ne 1 -or
        [string]$running.runs[0].reportName -cne "m3u-medium-10k-01.json") {
        throw "Focused M3U finalization did not preserve partial run evidence."
    }
    if ([string]$running.corpus.sha256 -cne $canonicalSha -or
        [long]$running.corpus.utf8ByteCount -ne 12345) {
        throw "Focused M3U finalization did not preserve corpus identity."
    }

    if ([string]$passed.status -cne "passed" -or
        [string]$passed.completedAtUtc -cne "2026-08-08T00:01:00.0000000Z" -or
        [string]$passed.analysisOutput -cne "m3u-large-50k-variance.json") {
        throw "Focused M3U finalizer modified an already-passed manifest."
    }

    Write-Host "Focused M3U series finalizer contract is valid."
} finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}
