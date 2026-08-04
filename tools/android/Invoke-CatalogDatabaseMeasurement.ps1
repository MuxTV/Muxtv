[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$SourceCommit,

    [ValidatePattern('^[a-z0-9][a-z0-9._-]{0,63}$')]
    [string]$RunnerLabel = "local-android-tv",

    [ValidateRange(0, 20)]
    [int]$Warmups = 1,

    [ValidateRange(5, 100)]
    [int]$Iterations = 5,

    [ValidateSet(10000)]
    [int]$EntryCount = 10000,

    [ValidatePattern('^[a-z0-9][a-z0-9._-]{0,63}\.json$')]
    [string]$OutputName = "catalog-database-measurement.json",

    [string]$EvidenceDirectory = ".work/evidence/catalog-database-measurement",

    [switch]$NoDaemon
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$LASTEXITCODE = 0
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

. (Join-Path $PSScriptRoot "AndroidSdk.ps1")

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
if (-not (Test-Path $gradleWrapper -PathType Leaf)) {
    throw "Gradle wrapper was not found."
}
if ([string]::IsNullOrWhiteSpace($env:ANDROID_SERIAL)) {
    throw "ANDROID_SERIAL must select one booted Android device."
}

$tools = Get-AndroidSdkTools
$serial = $env:ANDROID_SERIAL.Trim()
$deviceStateOutput = @(& $tools.Adb -s $serial get-state 2>$null)
$deviceStateExitCode = $LASTEXITCODE
$deviceState = if ($deviceStateOutput.Count -gt 0) {
    ([string]$deviceStateOutput[0]).Trim()
} else {
    ""
}
if ($deviceStateExitCode -ne 0 -or $deviceState -ne "device") {
    throw "The selected Android device is not ready."
}

$resolvedEvidenceDirectory = if ([System.IO.Path]::IsPathRooted($EvidenceDirectory)) {
    $EvidenceDirectory
} else {
    Join-Path $repositoryRoot $EvidenceDirectory
}
New-Item -ItemType Directory -Force -Path $resolvedEvidenceDirectory | Out-Null
$logPath = Join-Path $resolvedEvidenceDirectory "catalog-database-measurement.log"
$reportPath = Join-Path $resolvedEvidenceDirectory $OutputName
Remove-Item -Path $reportPath -Force -ErrorAction SilentlyContinue

$resultRoot = Join-Path $repositoryRoot "core\database\build\outputs\androidTest-results\connected\debug"
Remove-Item -Path $resultRoot -Recurse -Force -ErrorAction SilentlyContinue

$arguments = @(
    ":core:database:connectedDebugAndroidTest",
    "--stacktrace",
    "--console=plain",
    "-PcatalogMeasurements=true",
    "-Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.database.CatalogDatabaseMeasurementTest",
    "-Pandroid.testInstrumentationRunnerArguments.measurementSourceCommit=$SourceCommit",
    "-Pandroid.testInstrumentationRunnerArguments.measurementRunnerLabel=$RunnerLabel",
    "-Pandroid.testInstrumentationRunnerArguments.measurementWarmups=$Warmups",
    "-Pandroid.testInstrumentationRunnerArguments.measurementIterations=$Iterations",
    "-Pandroid.testInstrumentationRunnerArguments.measurementEntryCount=$EntryCount",
    "-Pandroid.testInstrumentationRunnerArguments.measurementOutputName=$OutputName"
)
if ($NoDaemon) {
    $arguments += "--no-daemon"
}

Set-Location $repositoryRoot
$output = @(& $gradleWrapper @arguments 2>&1)
$gradleExitCode = $LASTEXITCODE
$output | Tee-Object -FilePath $logPath | ForEach-Object { Write-Host $_ }
if ($gradleExitCode -ne 0) {
    throw "Catalog database measurement instrumentation failed with exit code $gradleExitCode."
}

$testLogs = @(
    Get-ChildItem -Path $resultRoot -Filter "test-results.log" -File -Recurse -ErrorAction SilentlyContinue |
        Sort-Object -Property LastWriteTimeUtc -Descending
)
if ($testLogs.Count -ne 1) {
    throw "Catalog database measurement produced an unexpected test-result log count."
}
$testLogContent = Get-Content -Path $testLogs[0].FullName -Raw -Encoding utf8
$resultPattern = '(?m)^INSTRUMENTATION_RESULT: catalogDatabaseMeasurementReportBase64=(?<value>[A-Za-z0-9+/=]+)\s*$'
$resultMatch = [regex]::Match($testLogContent, $resultPattern)
if (-not $resultMatch.Success) {
    throw "Catalog database measurement result payload was not reported by instrumentation."
}
try {
    $reportBytes = [Convert]::FromBase64String($resultMatch.Groups["value"].Value)
    [System.IO.File]::WriteAllBytes($reportPath, $reportBytes)
} catch {
    throw "Catalog database measurement result payload could not be decoded."
}
if (-not (Test-Path $reportPath -PathType Leaf) -or (Get-Item $reportPath).Length -le 0) {
    throw "Catalog database measurement report was not materialized."
}

try {
    $report = Get-Content -Path $reportPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 20
} catch {
    throw "Catalog database measurement report is not valid JSON."
}

if ([int]$report.schemaVersion -ne 1 -or [int]$report.methodVersion -ne 2) {
    throw "Catalog database measurement report schema is unsupported."
}
if ([string]$report.buildMode -cne "debug-instrumentation") {
    throw "Catalog database measurement build mode is unsupported."
}
if ([bool]$report.thresholdApplied) {
    throw "Catalog database measurement unexpectedly applied a threshold."
}
if ([string]$report.sourceCommit -cne $SourceCommit) {
    throw "Catalog database measurement report commit does not match the requested head."
}
if ([int]$report.failureCount -ne 0) {
    throw "Catalog database measurement report contains failed samples."
}
if ([int]$report.workload.entryCount -ne $EntryCount -or
    [int]$report.workload.batchSize -ne 250 -or
    [int]$report.workload.firstPageLimit -ne 100 -or
    [int]$report.workload.sourceOverviewCount -ne 32 -or
    [int]$report.workload.measuredIterations -ne $Iterations) {
    throw "Catalog database measurement workload does not match the requested contract."
}
if ([int]$report.fixture.entryCount -ne $EntryCount -or
    ([string]$report.fixture.sha256) -notmatch '^[0-9a-f]{64}$') {
    throw "Catalog database measurement fixture identity is invalid."
}

$expectedOperations = [ordered]@{
    "stage-batch-250" = 250
    "stage-total-10k" = 10000
    "activate-10k" = 10000
    "active-channel-first-page" = 100
    "search-exact-number-10k" = 1
    "search-selective-seed-10k" = 1
    "source-overview-32" = 32
}
$operations = @($report.operations)
if ($operations.Count -ne $expectedOperations.Count) {
    throw "Catalog database measurement report has an unexpected operation count."
}
for ($index = 0; $index -lt $expectedOperations.Count; $index++) {
    $expectedId = @($expectedOperations.Keys)[$index]
    $operation = $operations[$index]
    if ([string]$operation.operationId -cne $expectedId) {
        throw "Catalog database measurement operation order is invalid."
    }
    if ([int]$operation.expectedResultCount -ne [int]$expectedOperations[$expectedId]) {
        throw "Catalog database measurement expected result count is invalid."
    }
    $samples = @($operation.rawSamples)
    if ($samples.Count -ne $Iterations) {
        throw "Catalog database measurement raw sample count is invalid."
    }
    foreach ($sample in $samples) {
        if ([long]$sample.wallTimeNanos -le 0 -or
            [int]$sample.resultCount -ne [int]$expectedOperations[$expectedId] -or
            [long]$sample.databaseBytes -lt 0 -or
            [long]$sample.walBytes -lt 0 -or
            [long]$sample.shmBytes -lt 0) {
            throw "Catalog database measurement sample invariant failed."
        }
    }
}

@(
    "status=passed"
    "source_commit=$SourceCommit"
    "runner_label=$RunnerLabel"
    "build_mode=$($report.buildMode)"
    "api_level=$($report.environment.apiLevel)"
    "fixture_sha256=$($report.fixture.sha256)"
    "operation_count=$($operations.Count)"
    "samples_per_operation=$Iterations"
    "threshold_applied=false"
) | Add-Content -Path $logPath -Encoding utf8

Write-Host "Catalog database measurement passed."
Write-Host "report=$OutputName"
Write-Host "operations=$($operations.Count)"
Write-Host "samplesPerOperation=$Iterations"
Write-Host "thresholdApplied=false"
