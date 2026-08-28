[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$runnerPath = Join-Path $repositoryRoot "core\database\src\debug\kotlin\app\muxtv\database\measurement\CatalogDatabaseMeasurementRunnerV4.kt"
$testPath = Join-Path $repositoryRoot "core\database\src\androidTest\kotlin\app\muxtv\database\CatalogDatabaseMeasurementTest.kt"
$workflowPath = Join-Path $repositoryRoot ".github\workflows\m0-catalog-measurement-correctness.yml"
$hostedRunnerPath = Join-Path $repositoryRoot "tools\ci\Run-HostedCatalogMeasurementCorrectness.sh"
$messages = [System.Collections.Generic.List[string]]::new()

if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) {
    $messages.Add("Catalog database M0 v4 measurement runner is missing.")
} else {
    $runner = Get-Content -LiteralPath $runnerPath -Raw -Encoding utf8
    foreach ($token in @(
        "CatalogDatabaseMeasurementRunnerV4",
        "METHOD_VERSION = 4",
        "fresh-file-per-repetition-shared-scenarios",
        "measureLifecycleRepetition(",
        "fixture.batches.forEach",
        "handle.prepareActiveEpg(fixture)",
        "captureQueryTrace = false",
        "expectedCatalogSearchBoundaryEpochMillis("
    )) {
        if ($runner -notmatch [regex]::Escape($token)) {
            $messages.Add("Catalog M0 v4 runner is missing required methodology token: $token")
        }
    }
    if ($runner -match [regex]::Escape("check(snapshot.nextBoundaryEpochMillis == FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS)")) {
        $messages.Add("Catalog measurement runner still requires the global first-channel programme boundary.")
    }
}

if (-not (Test-Path -LiteralPath $testPath -PathType Leaf)) {
    $messages.Add("Catalog database measurement instrumentation test is missing.")
} else {
    $test = Get-Content -LiteralPath $testPath -Raw -Encoding utf8
    foreach ($token in @(
        "CatalogDatabaseMeasurementRunnerV4",
        "MuxTvM0Measurement",
        "Log.i(PROGRESS_TAG, message)"
    )) {
        if ($test -notmatch [regex]::Escape($token)) {
            $messages.Add("Catalog M0 instrumentation test is missing required progress/methodology token: $token")
        }
    }
}

if (-not (Test-Path -LiteralPath $workflowPath -PathType Leaf)) {
    $messages.Add("Hosted M0 catalog measurement correctness workflow is missing.")
} else {
    $workflow = Get-Content -LiteralPath $workflowPath -Raw -Encoding utf8
    foreach ($token in @(
        "core/database/src/debug/kotlin/app/muxtv/database/measurement/**",
        "core/database/src/androidTest/kotlin/app/muxtv/database/CatalogDatabaseMeasurementTest.kt",
        "core/database/src/test/kotlin/app/muxtv/database/measurement/**",
        "MuxTV_TV_CURRENT_API36",
        "Run-HostedCatalogMeasurementCorrectness.sh",
        "ubuntu-latest",
        "thresholdApplied=false"
    )) {
        if ($workflow -notmatch [regex]::Escape($token)) {
            $messages.Add("M0 catalog measurement workflow is missing required contract token: $token")
        }
    }
    if ($workflow -match "self-hosted" -or $workflow -match "MuxTV_TV_OLD_API26") {
        $messages.Add("M0 catalog measurement correctness must be a focused hosted API36 lane, not a self-hosted or two-device performance series.")
    }
}

if (-not (Test-Path -LiteralPath $hostedRunnerPath -PathType Leaf)) {
    $messages.Add("Hosted M0 catalog measurement runner script is missing.")
} else {
    $hostedRunner = Get-Content -LiteralPath $hostedRunnerPath -Raw -Encoding utf8
    foreach ($token in @(
        "CatalogDatabaseMeasurementTest",
        "-PcatalogMeasurements=true",
        "measurementWarmups=0",
        "measurementIterations=5",
        "measurementEntryCount=50000",
        "methodVersion",
        "github-hosted-linux-api36-m0-v2",
        "Assert-AndroidTestResults.ps1",
        "catalogDatabaseMeasurementReportBase64",
        "thresholdApplied"
    )) {
        if ($hostedRunner -notmatch [regex]::Escape($token)) {
            $messages.Add("Hosted M0 catalog measurement runner is missing required contract token: $token")
        }
    }
    if ($hostedRunner -notmatch 'report\.get\("methodVersion"\) != 4') {
        $messages.Add("Hosted M0 catalog measurement runner does not require methodVersion=4.")
    }
}

if ($messages.Count -gt 0) {
    $message = "M0 catalog measurement correctness contract failed." + [Environment]::NewLine +
        [string]::Join([Environment]::NewLine, $messages)
    Write-Host $message
    throw "M0 catalog measurement correctness contract failed."
}

Write-Host "M0 catalog measurement v4 routing, lifecycle reuse, progress and oracle contracts are valid."
