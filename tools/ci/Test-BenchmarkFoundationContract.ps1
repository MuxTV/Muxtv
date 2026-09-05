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
    'name: Benchmark JMH dry run',
    'runs-on: windows-latest',
    ':benchmark:jvm:jmh',
    '-PmuxtvJmhDryRun=true',
    'name: Benchmark Macrobenchmark API36 dry run',
    'runs-on: ubuntu-latest',
    'ReactiveCircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d',
    'api-level: 36',
    'target: android-tv',
    'arch: x86_64',
    'profile: tv_1080p',
    'avd-name: MuxTV_TV_CURRENT_API36',
    'connectedBenchmarkReleaseAndroidTest',
    'androidx.benchmark.dryRunMode.enable=true',
    'androidx.benchmark.enabledRules=Macrobenchmark',
    'Assert-AndroidTestResults.ps1',
    'name: Benchmark foundation dry run',
    'uses: ./.github/actions/upload-evidence-with-retry'
)) {
    if ($workflow.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Benchmark workflow is missing hosted dry-run contract token: $token"
    }
}
foreach ($forbiddenToken in @(
    'self-hosted',
    'Assert-SelfHostedRunnerPreflight.ps1',
    'Reset-SelfHostedAndroidState.ps1',
    'Invoke-BenchmarkDryRun.ps1'
)) {
    if ($workflow.IndexOf($forbiddenToken, [System.StringComparison]::Ordinal) -ge 0) {
        throw "Hosted benchmark workflow still contains legacy runner ownership token: $forbiddenToken"
    }
}
if ($workflow -match '(?mi)^\s*uses:\s*actions/upload-artifact@') {
    throw "Benchmark workflow must publish through the shared bounded evidence action."
}

# Keep the Windows helper valid as an optional local developer seam. Hosted CI no
# longer depends on it, but its dry-run semantics remain useful for local diagnosis.
$benchmarkHarness = Read-RequiredFile "tools\android\Invoke-BenchmarkDryRun.ps1"
foreach ($token in @(
    'connectedBenchmarkReleaseAndroidTest',
    'androidx.benchmark.dryRunMode.enable=true',
    'androidx.benchmark.enabledRules=Macrobenchmark',
    'Benchmark evidence commit mismatch',
    '$totalTestCount - $skippedCount'
)) {
    if ($benchmarkHarness.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Local benchmark device harness is missing contract token: $token"
    }
}

# O2.4: prove that the Benchmark 1.5 target variant can merge AndroidX Tracing 2.0
# in-process packets into its system Perfetto trace without enabling persistent trace
# storage in production/release variants.
$versionCatalog = Read-RequiredFile "gradle\libs.versions.toml"
foreach ($token in @(
    'benchmark = "1.5.0-rc02"',
    'androidx-tracing-wire = { module = "androidx.tracing:tracing-wire", version.ref = "tracing" }'
)) {
    if ($versionCatalog.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "O2.4 benchmark in-process tracing dependency contract is missing: $token"
    }
}

foreach ($token in @(
    'create("benchmarkRelease")',
    'initWith(getByName("release"))',
    'matchingFallbacks += listOf("release")',
    'add("benchmarkReleaseImplementation", libs.androidx.tracing.wire)'
)) {
    if ($appBuild.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "O2.4 target app benchmark-only tracing contract is missing: $token"
    }
}
foreach ($forbiddenToken in @(
    'implementation(libs.androidx.tracing.wire)',
    'releaseImplementation(libs.androidx.tracing.wire)',
    'add("releaseImplementation", libs.androidx.tracing.wire)'
)) {
    if ($appBuild.IndexOf($forbiddenToken, [System.StringComparison]::Ordinal) -ge 0) {
        throw "O2.4 must not persist AndroidX in-process trace files in production/release: $forbiddenToken"
    }
}

foreach ($token in @('runSearchTraceEvidence', 'search-input', 'search-status', 'setText(TRACE_SEARCH_QUERY)')) {
    if ($journeys.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "O2.4 focused trace journey is missing: $token"
    }
}
foreach ($token in @(
    'fun searchInProcessTraceEvidence()',
    'TraceSectionMetric(',
    'sectionName = "MuxTv.Search"',
    'mode = TraceSectionMetric.Mode.Count',
    'iterations = 1'
)) {
    if ($macrobenchmarks.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "O2.4 merged trace evidence benchmark is missing: $token"
    }
}

$traceWorkflow = Read-RequiredFile ".github\workflows\benchmark-in-process-tracing.yml"
foreach ($token in @(
    'name: Benchmark in-process trace API36 evidence',
    'pull_request:',
    'workflow_dispatch:',
    'ref: ${{ github.event_name == ''pull_request'' && github.event.pull_request.head.sha || github.sha }}',
    'Assert-EvidenceCommit.ps1',
    'connectedBenchmarkReleaseAndroidTest',
    'app.muxtv.benchmark.MuxTvMacrobenchmarks#searchInProcessTraceEvidence',
    'androidx.benchmark.enabledRules=Macrobenchmark',
    'uses: ./.github/actions/upload-evidence-with-retry',
    'connected_android_test_additional_output'
)) {
    if ($traceWorkflow.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
        throw "O2.4 exact trace evidence workflow is missing contract token: $token"
    }
}
if ($traceWorkflow.IndexOf('androidx.benchmark.dryRunMode.enable=true', [System.StringComparison]::Ordinal) -ge 0) {
    throw "O2.4 exact trace evidence must not run in Macrobenchmark dry-run mode."
}

Write-Host "Benchmark foundation contract passed for hosted dry-run CI and O2.4 in-process trace evidence."
