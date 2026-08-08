[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$finalizerScript = Join-Path $PSScriptRoot "Finalize-MeasurementSeriesEvidence.ps1"
$atomicPublicationContract = Join-Path $PSScriptRoot "Test-M3uSeriesManifestPublicationContract.ps1"
if (-not (Test-Path $finalizerScript -PathType Leaf)) {
    throw "Measurement series finalizer was not found."
}
if (-not (Test-Path $atomicPublicationContract -PathType Leaf)) {
    throw "Focused M3U manifest publication contract was not found."
}

$testRoot = Join-Path $repositoryRoot ".work\evidence\m3u-finalizer-contract"
Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $testRoot | Out-Null

$canonicalSha = "a" * 64
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

function Write-FocusedFixtureManifest {
    param(
        [Parameter(Mandatory)][string]$DirectoryName,
        [Parameter(Mandatory)][string]$Commit,
        [Parameter(Mandatory)][string]$Profile,
        [Parameter(Mandatory)][string]$Status,
        [AllowNull()][string]$CompletedAtUtc,
        [AllowNull()][string]$AnalysisOutput,
        [AllowNull()][string]$FailureType,
        [AllowNull()][object]$FailureLine
    )

    $directory = Join-Path $testRoot $DirectoryName
    New-Item -ItemType Directory -Path $directory | Out-Null
    $path = Join-Path $directory "m3u-series-run-manifest.json"
    [ordered]@{
        schemaVersion = 1
        repository = "MuxTV/Muxtv"
        branch = "finalizer-contract"
        commit = $Commit
        profile = $Profile
        seed = 20260728
        repetitions = 5
        warmups = 2
        iterations = 5
        runnerLabel = "self-hosted-windows-x64-v1"
        claimEligible = $true
        thresholdApplied = $false
        status = $Status
        startedAtUtc = "2026-08-08T00:00:00.0000000Z"
        completedAtUtc = $CompletedAtUtc
        corpus = $corpus
        runs = $partialRuns
        analysisOutput = $AnalysisOutput
        failureType = $FailureType
        failureLine = $FailureLine
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $path -Encoding utf8
    return $path
}

$runningManifestPath = Write-FocusedFixtureManifest `
    -DirectoryName "running" `
    -Commit ("1" * 40) `
    -Profile "medium-10k" `
    -Status "running" `
    -CompletedAtUtc $null `
    -AnalysisOutput $null `
    -FailureType $null `
    -FailureLine $null
$passedManifestPath = Write-FocusedFixtureManifest `
    -DirectoryName "passed" `
    -Commit ("2" * 40) `
    -Profile "large-50k" `
    -Status "passed" `
    -CompletedAtUtc "2026-08-08T00:01:00.0000000Z" `
    -AnalysisOutput "m3u-large-50k-variance.json" `
    -FailureType $null `
    -FailureLine $null
$failedManifestPath = Write-FocusedFixtureManifest `
    -DirectoryName "failed" `
    -Commit ("3" * 40) `
    -Profile "medium-10k" `
    -Status "failed" `
    -CompletedAtUtc "2026-08-08T00:02:00.0000000Z" `
    -AnalysisOutput $null `
    -FailureType "System.InvalidOperationException" `
    -FailureLine 42
$interruptedManifestPath = Write-FocusedFixtureManifest `
    -DirectoryName "interrupted" `
    -Commit ("4" * 40) `
    -Profile "large-50k" `
    -Status "interrupted" `
    -CompletedAtUtc "2026-08-08T00:03:00.0000000Z" `
    -AnalysisOutput $null `
    -FailureType $null `
    -FailureLine $null

$passedBefore = Get-Content -LiteralPath $passedManifestPath -Raw -Encoding utf8
$failedBefore = Get-Content -LiteralPath $failedManifestPath -Raw -Encoding utf8
$interruptedBefore = Get-Content -LiteralPath $interruptedManifestPath -Raw -Encoding utf8

try {
    & $finalizerScript -EvidenceRoot $testRoot

    $running = Get-Content -LiteralPath $runningManifestPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 20
    $passedAfter = Get-Content -LiteralPath $passedManifestPath -Raw -Encoding utf8
    $failedAfter = Get-Content -LiteralPath $failedManifestPath -Raw -Encoding utf8
    $interruptedAfter = Get-Content -LiteralPath $interruptedManifestPath -Raw -Encoding utf8

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

    if ($passedAfter -cne $passedBefore) {
        throw "Focused M3U finalizer modified an already-passed manifest."
    }
    if ($failedAfter -cne $failedBefore) {
        throw "Focused M3U finalizer modified an already-failed manifest."
    }
    if ($interruptedAfter -cne $interruptedBefore) {
        throw "Focused M3U finalizer modified an already-interrupted manifest."
    }

    & $atomicPublicationContract
    Write-Host "Focused M3U series finalizer contract is valid."
} finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}
