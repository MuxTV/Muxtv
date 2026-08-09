[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Read-RequiredFile {
    param([Parameter(Mandatory)][string]$RelativePath)

    $path = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing benchmark foundation file: $RelativePath"
    }
    return Get-Content -LiteralPath $path -Raw -Encoding utf8
}

$settings = Read-RequiredFile "settings.gradle.kts"
foreach ($module in @(':benchmark:macrobenchmark', ':benchmark:jvm')) {
    if ($settings.IndexOf($module, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Benchmark module is not included: $module"
    }
}

$appBuild = Read-RequiredFile "app\tv\build.gradle.kts"
foreach ($token in @('libs.plugins.androidx.baselineprofile', 'baselineProfile(project(":benchmark:macrobenchmark"))')) {
    if ($appBuild.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "App is missing Baseline Profile consumer contract: $token"
    }
}

$appManifest = Read-RequiredFile "app\tv\src\main\AndroidManifest.xml"
if ($appManifest.IndexOf('<profileable android:shell="true"', [System.StringComparison]::Ordinal) -lt 0) {
    throw "Target app is not shell-profileable for Macrobenchmark."
}

$macroBuild = Read-RequiredFile "benchmark\macrobenchmark\build.gradle.kts"
foreach ($token in @(
    'alias(libs.plugins.android.test)',
    'alias(libs.plugins.androidx.baselineprofile)',
    'targetProjectPath = ":app:tv"',
    'libs.benchmark.macro.junit4',
    'libs.androidx.test.uiautomator'
)) {
    if ($macroBuild.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Macrobenchmark producer is missing contract token: $token"
    }
}

$journeys = Read-RequiredFile "benchmark\macrobenchmark\src\main\kotlin\app\muxtv\benchmark\MuxTvCriticalUserJourneys.kt"
foreach ($token in @('openChannels', 'openSearch', 'openGuide', 'openSources', 'openDoctor')) {
    if ($journeys.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Reusable Macrobenchmark journey is missing: $token"
    }
}

$profileGenerator = Read-RequiredFile "benchmark\macrobenchmark\src\main\kotlin\app\muxtv\benchmark\BaselineProfileGenerator.kt"
foreach ($token in @('BaselineProfileRule', 'includeInStartupProfile = true', 'MuxTvCriticalUserJourneys')) {
    if ($profileGenerator.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Baseline Profile generator is missing contract token: $token"
    }
}

$macrobenchmarks = Read-RequiredFile "benchmark\macrobenchmark\src\main\kotlin\app\muxtv\benchmark\MuxTvMacrobenchmarks.kt"
foreach ($token in @('StartupTimingMetric', 'FrameTimingMetric', 'CompilationMode.Partial', 'measureRepeated')) {
    if ($macrobenchmarks.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Macrobenchmark suite is missing contract token: $token"
    }
}

$jmhBuild = Read-RequiredFile "benchmark\jvm\build.gradle.kts"
foreach ($token in @('alias(libs.plugins.jmh)', 'jmhVersion = "1.37"', 'profilers = listOf("gc")', 'resultFormat = "JSON"')) {
    if ($jmhBuild.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "JMH module is missing contract token: $token"
    }
}

foreach ($benchmarkFile in @(
    "benchmark\jvm\src\jmh\kotlin\app\muxtv\benchmark\PlaybackRecoveryBenchmark.kt",
    "benchmark\jvm\src\jmh\kotlin\app\muxtv\benchmark\SearchNormalizationBenchmark.kt",
    "benchmark\jvm\src\jmh\kotlin\app\muxtv\benchmark\StreamingParserBenchmark.kt",
    "benchmark\jvm\src\jmh\kotlin\app\muxtv\benchmark\StreamingXmltvParserBenchmark.kt"
)) {
    $null = Read-RequiredFile $benchmarkFile
}

$workflow = Read-RequiredFile ".github\workflows\benchmark-foundation.yml"
foreach ($token in @(
    'runs-on: [self-hosted, Windows, X64, muxtv-android, muxtv-device]',
    'Invoke-BenchmarkDryRun.ps1',
    'Reset-SelfHostedAndroidState.ps1',
    'actions/upload-artifact'
)) {
    if ($workflow.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Benchmark workflow is missing contract token: $token"
    }
}

$benchmarkHarness = Read-RequiredFile "tools\android\Invoke-BenchmarkDryRun.ps1"
foreach ($token in @(
    'connectedBenchmarkReleaseAndroidTest',
    'androidx.benchmark.dryRunMode.enable=true',
    'androidx.benchmark.enabledRules=Macrobenchmark',
    'Benchmark evidence commit mismatch'
)) {
    if ($benchmarkHarness.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Benchmark device harness is missing contract token: $token"
    }
}

Write-Host "Benchmark foundation contract passed."
