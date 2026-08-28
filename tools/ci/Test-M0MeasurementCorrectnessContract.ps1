[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$runnerPath = Join-Path $repositoryRoot "core\database\src\debug\kotlin\app\muxtv\database\measurement\CatalogDatabaseMeasurementRunner.kt"
$helperPath = Join-Path $repositoryRoot "core\database\src\debug\kotlin\app\muxtv\database\measurement\CatalogSearchBoundaryExpectation.kt"
$helperTestPath = Join-Path $repositoryRoot "core\database\src\test\kotlin\app\muxtv\database\measurement\CatalogSearchBoundaryExpectationTest.kt"
$correctnessTestPath = Join-Path $repositoryRoot "core\database\src\androidTest\kotlin\app\muxtv\database\CatalogDatabaseMeasurementCorrectnessTest.kt"
$workflowPath = Join-Path $repositoryRoot ".github\workflows\m0-catalog-measurement-correctness.yml"
$hostedRunnerPath = Join-Path $repositoryRoot "tools\ci\Run-HostedCatalogMeasurementCorrectness.sh"
$messages = [System.Collections.Generic.List[string]]::new()

if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) {
    $messages.Add("Catalog database measurement runner is missing.")
} else {
    $runner = Get-Content -LiteralPath $runnerPath -Raw -Encoding utf8
    if ($runner -notmatch [regex]::Escape("expectedCatalogSearchBoundaryEpochMillis(")) {
        $messages.Add("Catalog measurement runner does not derive nextBoundary from the published result set.")
    }
    if ($runner -match [regex]::Escape("check(snapshot.nextBoundaryEpochMillis == FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS)")) {
        $messages.Add("Catalog measurement runner still requires the global first-channel programme boundary.")
    }
}

foreach ($path in @($helperPath, $helperTestPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        $messages.Add("Catalog search boundary oracle/helper regression coverage is missing: $path")
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
        'text = "Synthetic"',
        "FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS + finalIndex",
        "FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS"
    )) {
        if ($correctnessTest -notmatch [regex]::Escape($token)) {
            $messages.Add("Bounded M0 correctness test is missing required selective/broad boundary token: $token")
        }
    }
    if ($correctnessTest -match "50_000" -or $correctnessTest -match "measuredIterations") {
        $messages.Add("Automatic M0 correctness must remain a bounded 10k correctness fixture, not a timing series.")
    }
}

if (-not (Test-Path -LiteralPath $workflowPath -PathType Leaf)) {
    $messages.Add("Hosted M0 catalog correctness workflow is missing.")
} else {
    $workflow = Get-Content -LiteralPath $workflowPath -Raw -Encoding utf8
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
        $messages.Add("M0 correctness must be a focused hosted API36 lane.")
    }
    if ($workflow -match "50000" -or $workflow -match "measurementIterations=5" -or $workflow -match "catalog-measurement-stress") {
        $messages.Add("M0 pull-request correctness must not execute or route through the manual 50k stress series.")
    }
}

if (-not (Test-Path -LiteralPath $hostedRunnerPath -PathType Leaf)) {
    $messages.Add("Hosted M0 bounded correctness runner script is missing.")
} else {
    $hostedRunner = Get-Content -LiteralPath $hostedRunnerPath -Raw -Encoding utf8
    foreach ($token in @(
        "CatalogDatabaseMeasurementCorrectnessTest",
        "Assert-AndroidTestResults.ps1",
        "entryCount=10000",
        "mode=correctness",
        "thresholdApplied=false",
        "claimEligible=false"
    )) {
        if ($hostedRunner -notmatch [regex]::Escape($token)) {
            $messages.Add("Hosted M0 bounded correctness runner is missing required token: $token")
        }
    }
    if ($hostedRunner -match "catalogMeasurements=true" -or $hostedRunner -match "measurementIterations=5" -or $hostedRunner -match "measurementEntryCount=50000") {
        $messages.Add("Bounded correctness runner must not invoke the 50k timing series.")
    }
}

if ($messages.Count -gt 0) {
    $message = "M0 catalog measurement correctness contract failed." + [Environment]::NewLine +
        [string]::Join([Environment]::NewLine, $messages)
    Write-Host $message
    throw "M0 catalog measurement correctness contract failed."
}

Write-Host "M0 published-result boundary oracle and bounded 10k API36 correctness contracts are valid; 50k timing remains manual under issue #27."
