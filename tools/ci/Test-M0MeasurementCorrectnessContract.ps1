[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$v4RunnerPath = Join-Path $repositoryRoot "core\database\src\debug\kotlin\app\muxtv\database\measurement\CatalogDatabaseMeasurementRunnerV4.kt"
$correctnessTestPath = Join-Path $repositoryRoot "core\database\src\androidTest\kotlin\app\muxtv\database\CatalogDatabaseMeasurementCorrectnessTest.kt"
$measurementTestPath = Join-Path $repositoryRoot "core\database\src\androidTest\kotlin\app\muxtv\database\CatalogDatabaseMeasurementTest.kt"
$correctnessWorkflowPath = Join-Path $repositoryRoot ".github\workflows\m0-catalog-measurement-correctness.yml"
$stressWorkflowPath = Join-Path $repositoryRoot ".github\workflows\m0-catalog-measurement-stress.yml"
$correctnessRunnerPath = Join-Path $repositoryRoot "tools\ci\Run-HostedCatalogMeasurementCorrectness.sh"
$stressRunnerPath = Join-Path $repositoryRoot "tools\ci\Run-HostedCatalogMeasurementStress.sh"
$messages = [System.Collections.Generic.List[string]]::new()

if (-not (Test-Path -LiteralPath $v4RunnerPath -PathType Leaf)) {
    $messages.Add("Catalog database M0 v4 stress runner is missing.")
} else {
    $runner = Get-Content -LiteralPath $v4RunnerPath -Raw -Encoding utf8
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
            $messages.Add("Catalog M0 v4 stress runner is missing required methodology token: $token")
        }
    }
    if ($runner -match [regex]::Escape("check(snapshot.nextBoundaryEpochMillis == FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS)")) {
        $messages.Add("Catalog measurement runner still requires the global first-channel programme boundary.")
    }
}

if (-not (Test-Path -LiteralPath $correctnessTestPath -PathType Leaf)) {
    $messages.Add("Bounded M0 catalog correctness Android test is missing.")
} else {
    $correctnessTest = Get-Content -LiteralPath $correctnessTestPath -Raw -Encoding utf8
    foreach ($token in @(
        "CatalogDatabaseMeasurementCorrectnessTest",
        "CORRECTNESS_ENTRY_COUNT = 10_000",
        "RoomChannelSearchRepository(",
        "text = CORRECTNESS_ENTRY_COUNT.toString()",
        "text = \"Synthetic\"",
        "FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS + finalIndex",
        "FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS"
    )) {
        if ($correctnessTest -notmatch [regex]::Escape($token)) {
            $messages.Add("Bounded M0 correctness test is missing required selective/broad boundary token: $token")
        }
    }
    if ($correctnessTest -match "50_000" -or $correctnessTest -match "measuredIterations") {
        $messages.Add("The automatic M0 correctness test must remain a bounded 10k correctness fixture, not a timing series.")
    }
}

if (-not (Test-Path -LiteralPath $measurementTestPath -PathType Leaf)) {
    $messages.Add("Catalog database v4 measurement instrumentation test is missing.")
} else {
    $measurementTest = Get-Content -LiteralPath $measurementTestPath -Raw -Encoding utf8
    foreach ($token in @(
        "CatalogDatabaseMeasurementRunnerV4",
        "MuxTvM0Measurement",
        "Log.i(PROGRESS_TAG, message)"
    )) {
        if ($measurementTest -notmatch [regex]::Escape($token)) {
            $messages.Add("Catalog M0 stress instrumentation test is missing required v4/progress token: $token")
        }
    }
}

if (-not (Test-Path -LiteralPath $correctnessWorkflowPath -PathType Leaf)) {
    $messages.Add("Hosted M0 catalog correctness workflow is missing.")
} else {
    $workflow = Get-Content -LiteralPath $correctnessWorkflowPath -Raw -Encoding utf8
    foreach ($token in @(
        "pull_request:",
        "CatalogDatabaseMeasurementCorrectnessTest.kt",
        "MuxTV_TV_CURRENT_API36",
        "Run-HostedCatalogMeasurementCorrectness.sh",
        "ubuntu-latest"
    )) {
        if ($workflow -notmatch [regex]::Escape($token)) {
            $messages.Add("M0 PR correctness workflow is missing required bounded-correctness token: $token")
        }
    }
    if ($workflow -match "self-hosted" -or $workflow -match "MuxTV_TV_OLD_API26") {
        $messages.Add("M0 correctness must be a focused hosted API36 lane, not a self-hosted or two-device performance series.")
    }
    if ($workflow -match "50000" -or $workflow -match "iterations=5" -or $workflow -match "Run-HostedCatalogMeasurementStress") {
        $messages.Add("The pull-request M0 correctness gate must not execute the manual 50k x 5 stress series.")
    }
}

if (-not (Test-Path -LiteralPath $correctnessRunnerPath -PathType Leaf)) {
    $messages.Add("Hosted M0 bounded correctness runner script is missing.")
} else {
    $correctnessRunner = Get-Content -LiteralPath $correctnessRunnerPath -Raw -Encoding utf8
    foreach ($token in @(
        "CatalogDatabaseMeasurementCorrectnessTest",
        "Assert-AndroidTestResults.ps1",
        "entryCount=10000",
        "mode=correctness",
        "thresholdApplied=false",
        "claimEligible=false"
    )) {
        if ($correctnessRunner -notmatch [regex]::Escape($token)) {
            $messages.Add("Hosted M0 bounded correctness runner is missing required token: $token")
        }
    }
    if ($correctnessRunner -match "catalogMeasurements=true" -or $correctnessRunner -match "measurementIterations=5" -or $correctnessRunner -match "measurementEntryCount=50000") {
        $messages.Add("The bounded correctness runner must not invoke the v4 50k timing series.")
    }
}

if (-not (Test-Path -LiteralPath $stressWorkflowPath -PathType Leaf)) {
    $messages.Add("Manual M0 catalog measurement stress workflow is missing.")
} else {
    $stressWorkflow = Get-Content -LiteralPath $stressWorkflowPath -Raw -Encoding utf8
    foreach ($token in @(
        "workflow_dispatch:",
        "MuxTV_TV_CURRENT_API36",
        "Run-HostedCatalogMeasurementStress.sh",
        "timeout-minutes: 180",
        "retention-days: 3",
        "thresholdApplied=false",
        "claimEligible=false"
    )) {
        if ($stressWorkflow -notmatch [regex]::Escape($token)) {
            $messages.Add("Manual M0 stress workflow is missing required descriptive-evidence token: $token")
        }
    }
    if ($stressWorkflow -match "pull_request:") {
        $messages.Add("The 50k x 5 M0 stress series must remain workflow_dispatch-only and must not become a pull-request gate.")
    }
}

if (-not (Test-Path -LiteralPath $stressRunnerPath -PathType Leaf)) {
    $messages.Add("Manual M0 catalog measurement stress runner is missing.")
} else {
    $stressRunner = Get-Content -LiteralPath $stressRunnerPath -Raw -Encoding utf8
    foreach ($token in @(
        "CatalogDatabaseMeasurementTest",
        "-PcatalogMeasurements=true",
        "measurementWarmups=0",
        "measurementIterations=5",
        "measurementEntryCount=50000",
        "github-hosted-linux-api36-m0-v4-stress",
        'report.get("methodVersion") != 4',
        "thresholdApplied=false",
        "claimEligible=false"
    )) {
        if ($stressRunner -notmatch [regex]::Escape($token)) {
            $messages.Add("Manual M0 stress runner is missing required 50k x 5 methodology token: $token")
        }
    }
}

if ($messages.Count -gt 0) {
    $message = "M0 catalog measurement correctness contract failed." + [Environment]::NewLine +
        [string]::Join([Environment]::NewLine, $messages)
    Write-Host $message
    throw "M0 catalog measurement correctness contract failed."
}

Write-Host "M0 bounded PR correctness and workflow_dispatch-only 50k v4 stress contracts are valid."
