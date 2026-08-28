[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$runnerPath = Join-Path $repositoryRoot "core\database\src\debug\kotlin\app\muxtv\database\measurement\CatalogDatabaseMeasurementRunnerV4.kt"
$testPath = Join-Path $repositoryRoot "core\database\src\androidTest\kotlin\app\muxtv\database\CatalogDatabaseMeasurementTest.kt"
$correctnessWorkflowPath = Join-Path $repositoryRoot ".github\workflows\m0-catalog-measurement-correctness.yml"
$stressWorkflowPath = Join-Path $repositoryRoot ".github\workflows\m0-catalog-measurement-stress.yml"
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
        "expectedCatalogSearchBoundaryEpochMillis(",
        "searchScenarios(fixture.workload.entryCount)",
        "entryCount - 1"
    )) {
        if ($runner -notmatch [regex]::Escape($token)) {
            $messages.Add("Catalog M0 v4 runner is missing required methodology token: $token")
        }
    }
    if ($runner -match [regex]::Escape("check(snapshot.nextBoundaryEpochMillis == FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS)")) {
        $messages.Add("Catalog measurement runner still requires the global first-channel programme boundary.")
    }
    if ($runner -match [regex]::Escape('SearchScenario("search-exact-number", "50000"')) {
        $messages.Add("Catalog M0 v4 search scenarios remain hard-coded to the 50k fixture instead of deriving the final entry from workload.entryCount.")
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

if (-not (Test-Path -LiteralPath $correctnessWorkflowPath -PathType Leaf)) {
    $messages.Add("Hosted M0 catalog measurement correctness workflow is missing.")
} else {
    $workflow = Get-Content -LiteralPath $correctnessWorkflowPath -Raw -Encoding utf8
    foreach ($token in @(
        "pull_request:",
        "MuxTV_TV_CURRENT_API36",
        "Run-HostedCatalogMeasurementCorrectness.sh",
        "ubuntu-latest",
        "MUXTV_MEASUREMENT_MODE: correctness",
        "MUXTV_MEASUREMENT_ENTRY_COUNT: '10000'",
        "MUXTV_MEASUREMENT_ITERATIONS: '1'",
        "thresholdApplied=false"
    )) {
        if ($workflow -notmatch [regex]::Escape($token)) {
            $messages.Add("M0 PR correctness workflow is missing required bounded-correctness token: $token")
        }
    }
    if ($workflow -match "self-hosted" -or $workflow -match "MuxTV_TV_OLD_API26") {
        $messages.Add("M0 catalog measurement correctness must be a focused hosted API36 lane, not a self-hosted or two-device performance series.")
    }
    if ($workflow -match "MUXTV_MEASUREMENT_ENTRY_COUNT: '50000'" -or $workflow -match "MUXTV_MEASUREMENT_ITERATIONS: '5'") {
        $messages.Add("The pull-request M0 correctness gate must not run the manual 50k x 5 stress series.")
    }
}

if (-not (Test-Path -LiteralPath $stressWorkflowPath -PathType Leaf)) {
    $messages.Add("Manual M0 catalog measurement stress workflow is missing.")
} else {
    $stressWorkflow = Get-Content -LiteralPath $stressWorkflowPath -Raw -Encoding utf8
    foreach ($token in @(
        "workflow_dispatch:",
        "MuxTV_TV_CURRENT_API36",
        "Run-HostedCatalogMeasurementCorrectness.sh",
        "MUXTV_MEASUREMENT_MODE: stress",
        "MUXTV_MEASUREMENT_ENTRY_COUNT: '50000'",
        "MUXTV_MEASUREMENT_ITERATIONS: '5'",
        "thresholdApplied=false",
        "claimEligible=false"
    )) {
        if ($stressWorkflow -notmatch [regex]::Escape($token)) {
            $messages.Add("Manual M0 stress workflow is missing required 50k descriptive-evidence token: $token")
        }
    }
    if ($stressWorkflow -match "pull_request:") {
        $messages.Add("The 50k x 5 M0 stress series must remain workflow_dispatch-only and must not become a pull-request gate.")
    }
}

if (-not (Test-Path -LiteralPath $hostedRunnerPath -PathType Leaf)) {
    $messages.Add("Hosted M0 catalog measurement runner script is missing.")
} else {
    $hostedRunner = Get-Content -LiteralPath $hostedRunnerPath -Raw -Encoding utf8
    foreach ($token in @(
        "CatalogDatabaseMeasurementTest",
        "-PcatalogMeasurements=true",
        "MUXTV_MEASUREMENT_MODE",
        "MUXTV_MEASUREMENT_ENTRY_COUNT",
        "MUXTV_MEASUREMENT_ITERATIONS",
        'measurementIterations="$MUXTV_MEASUREMENT_ITERATIONS"',
        'measurementEntryCount="$MUXTV_MEASUREMENT_ENTRY_COUNT"',
        "methodVersion",
        "Assert-AndroidTestResults.ps1",
        "catalogDatabaseMeasurementReportBase64",
        "thresholdApplied"
    )) {
        if ($hostedRunner -notmatch [regex]::Escape($token)) {
            $messages.Add("Hosted M0 catalog measurement runner is missing required parameterized contract token: $token")
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

Write-Host "M0 catalog measurement v4 bounded PR correctness and manual stress contracts are valid."
