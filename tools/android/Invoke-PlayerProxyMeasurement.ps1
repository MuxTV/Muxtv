[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$SourceCommit,

    [ValidatePattern('^[a-z0-9][a-z0-9._-]{0,63}$')]
    [string]$RunnerLabel = "local-android-tv",

    [ValidateRange(0, 20)]
    [int]$Warmups = 2,

    [Alias("Iterations")]
    [ValidateRange(5, 100)]
    [int]$Samples = 10,

    [ValidateRange(1, 100000)]
    [int]$OperationsPerSample = 1000,

    [ValidatePattern('^[a-z0-9][a-z0-9._-]{0,63}\.json$')]
    [string]$OutputName = "player-proxy-measurement.json",

    [string]$EvidenceDirectory = ".work/evidence/player-proxy-measurement",

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
$logPath = Join-Path $resolvedEvidenceDirectory "player-proxy-measurement.log"
$reportPath = Join-Path $resolvedEvidenceDirectory $OutputName
Remove-Item -Path $reportPath -Force -ErrorAction SilentlyContinue

$resultRoot = Join-Path $repositoryRoot "player\media3\build\outputs\androidTest-results\connected\debug"
Remove-Item -Path $resultRoot -Recurse -Force -ErrorAction SilentlyContinue

$arguments = @(
    ":player:media3:connectedDebugAndroidTest",
    "--stacktrace",
    "--console=plain",
    "-PplayerProxyMeasurements=true",
    "-Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.player.media3.PlayerProxyMeasurementTest",
    "-Pandroid.testInstrumentationRunnerArguments.playerMeasurementSourceCommit=$SourceCommit",
    "-Pandroid.testInstrumentationRunnerArguments.playerMeasurementRunnerLabel=$RunnerLabel",
    "-Pandroid.testInstrumentationRunnerArguments.playerMeasurementWarmups=$Warmups",
    "-Pandroid.testInstrumentationRunnerArguments.playerMeasurementSamples=$Samples",
    "-Pandroid.testInstrumentationRunnerArguments.playerMeasurementOperationsPerSample=$OperationsPerSample",
    "-Pandroid.testInstrumentationRunnerArguments.playerMeasurementOutputName=$OutputName"
)
if ($NoDaemon) {
    $arguments += "--no-daemon"
}

Set-Location $repositoryRoot
$output = @(& $gradleWrapper @arguments 2>&1)
$gradleExitCode = $LASTEXITCODE
$output | Tee-Object -FilePath $logPath | ForEach-Object { Write-Host $_ }
if ($gradleExitCode -ne 0) {
    throw "Player proxy measurement instrumentation failed with exit code $gradleExitCode."
}

$testLogs = @(
    Get-ChildItem -Path $resultRoot -Filter "test-results.log" -File -Recurse -ErrorAction SilentlyContinue |
        Sort-Object -Property LastWriteTimeUtc -Descending
)
if ($testLogs.Count -ne 1) {
    throw "Player proxy measurement produced an unexpected test-result log count."
}
$testLogContent = Get-Content -Path $testLogs[0].FullName -Raw -Encoding utf8
$resultPattern = '(?m)^INSTRUMENTATION_RESULT: playerProxyMeasurementReportBase64=(?<value>[A-Za-z0-9+/=]+)\s*$'
$resultMatch = [regex]::Match($testLogContent, $resultPattern)
if (-not $resultMatch.Success) {
    throw "Player proxy measurement result payload was not reported by instrumentation."
}
try {
    $reportBytes = [Convert]::FromBase64String($resultMatch.Groups["value"].Value)
    [System.IO.File]::WriteAllBytes($reportPath, $reportBytes)
} catch {
    throw "Player proxy measurement result payload could not be decoded."
}
if (-not (Test-Path $reportPath -PathType Leaf) -or (Get-Item $reportPath).Length -le 0) {
    throw "Player proxy measurement report was not materialized."
}

try {
    $report = Get-Content -Path $reportPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 20
} catch {
    throw "Player proxy measurement report is not valid JSON."
}

if ([int]$report.schemaVersion -ne 1 -or [int]$report.methodVersion -ne 1) {
    throw "Player proxy measurement report schema is unsupported."
}
if ([string]$report.buildMode -cne "debug-instrumentation") {
    throw "Player proxy measurement build mode is unsupported."
}
if ([bool]$report.thresholdApplied) {
    throw "Player proxy measurement unexpectedly applied a threshold."
}
if ([string]$report.sourceCommit -cne $SourceCommit) {
    throw "Player proxy measurement report commit does not match the requested head."
}
if ([int]$report.failureCount -ne 0) {
    throw "Player proxy measurement report contains failed samples."
}
if ([int]$report.workload.warmupSamples -ne $Warmups -or
    [int]$report.workload.measuredSamples -ne $Samples -or
    [int]$report.workload.operationsPerSample -ne $OperationsPerSample) {
    throw "Player proxy measurement workload does not match the requested contract."
}
if (([string]$report.requestProfileSha256) -notmatch '^[0-9a-f]{64}$') {
    throw "Player proxy measurement request profile identity is invalid."
}

$expectedOperations = @(
    "request-construct",
    "setup-envelope-roundtrip",
    "coordinator-install-active-clear",
    "coordinator-cancel-before-install",
    "registry-disconnect-reacquire"
)
$operations = @($report.operations)
if ($operations.Count -ne $expectedOperations.Count) {
    throw "Player proxy measurement report has an unexpected operation count."
}
for ($index = 0; $index -lt $expectedOperations.Count; $index++) {
    $operation = $operations[$index]
    if ([string]$operation.operationId -cne $expectedOperations[$index]) {
        throw "Player proxy measurement operation order is invalid."
    }
    if ([int]$operation.expectedSuccessfulResultCount -ne $OperationsPerSample) {
        throw "Player proxy measurement expected result count is invalid."
    }
    $rawSamples = @($operation.rawSamples)
    if ($rawSamples.Count -ne $Samples) {
        throw "Player proxy measurement raw sample count is invalid."
    }
    foreach ($sample in $rawSamples) {
        if ([long]$sample.batchWallTimeNanos -le 0 -or
            [int]$sample.operationCount -ne $OperationsPerSample -or
            [long]$sample.normalizedNanosPerOperation -le 0 -or
            [int]$sample.successfulResultCount -ne $OperationsPerSample) {
            throw "Player proxy measurement sample invariant failed."
        }
    }
}

@(
    "status=passed"
    "source_commit=$SourceCommit"
    "runner_label=$RunnerLabel"
    "build_mode=$($report.buildMode)"
    "api_level=$($report.environment.apiLevel)"
    "request_profile_sha256=$($report.requestProfileSha256)"
    "operation_count=$($operations.Count)"
    "samples_per_operation=$Samples"
    "operations_per_sample=$OperationsPerSample"
    "threshold_applied=false"
) | Add-Content -Path $logPath -Encoding utf8

Write-Host "Player proxy measurement passed."
Write-Host "report=$OutputName"
Write-Host "operations=$($operations.Count)"
Write-Host "samplesPerOperation=$Samples"
Write-Host "operationsPerSample=$OperationsPerSample"
Write-Host "thresholdApplied=false"
