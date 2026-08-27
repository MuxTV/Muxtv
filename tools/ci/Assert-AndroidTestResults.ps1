[CmdletBinding()]
param(
    [string]$RepositoryRoot = "",

    [string[]]$ModulePaths = @(
        "catalog/importer",
        "catalog/refresh",
        "core/credentials",
        "core/database",
        "player/media3",
        "app/tv"
    ),

    [string]$OutputPath = ".work/evidence/hosted-android/android-test-results.json"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
} else {
    $RepositoryRoot = (Resolve-Path $RepositoryRoot).Path
}

if (-not [System.IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath = Join-Path $RepositoryRoot $OutputPath
}

$moduleSummaries = [System.Collections.Generic.List[object]]::new()
$totalTests = 0
$totalFailures = 0
$totalErrors = 0
$totalSkipped = 0

foreach ($modulePath in $ModulePaths) {
    $normalizedModule = $modulePath -replace '/', [System.IO.Path]::DirectorySeparatorChar
    $resultRoot = Join-Path $RepositoryRoot (Join-Path $normalizedModule "build/outputs/androidTest-results")
    $resultFiles = @(
        if (Test-Path -LiteralPath $resultRoot -PathType Container) {
            Get-ChildItem -LiteralPath $resultRoot -Recurse -File -Filter "TEST-*.xml"
        }
    )

    if ($resultFiles.Count -eq 0) {
        throw "$modulePath produced no TEST-*.xml Android instrumentation results under $resultRoot."
    }

    $moduleTests = 0
    $moduleFailures = 0
    $moduleErrors = 0
    $moduleSkipped = 0

    foreach ($resultFile in $resultFiles) {
        [xml]$document = Get-Content -LiteralPath $resultFile.FullName -Raw
        $suites = @($document.SelectNodes("//testsuite"))
        if ($suites.Count -eq 0) {
            throw "$modulePath result file contains no testsuite element: $($resultFile.FullName)"
        }

        foreach ($suite in $suites) {
            $moduleTests += if ($null -ne $suite.Attributes["tests"]) { [int]$suite.Attributes["tests"].Value } else { 0 }
            $moduleFailures += if ($null -ne $suite.Attributes["failures"]) { [int]$suite.Attributes["failures"].Value } else { 0 }
            $moduleErrors += if ($null -ne $suite.Attributes["errors"]) { [int]$suite.Attributes["errors"].Value } else { 0 }
            $moduleSkipped += if ($null -ne $suite.Attributes["skipped"]) { [int]$suite.Attributes["skipped"].Value } else { 0 }
        }
    }

    if ($moduleTests -lt 1) {
        throw "$modulePath executed zero Android instrumentation tests."
    }
    if (($moduleFailures + $moduleErrors) -gt 0) {
        throw "$modulePath reports failures=$moduleFailures errors=$moduleErrors."
    }

    $moduleSummaries.Add([ordered]@{
        module = $modulePath
        tests = $moduleTests
        failures = $moduleFailures
        errors = $moduleErrors
        skipped = $moduleSkipped
        resultFiles = $resultFiles.Count
    })

    $totalTests += $moduleTests
    $totalFailures += $moduleFailures
    $totalErrors += $moduleErrors
    $totalSkipped += $moduleSkipped
}

$summary = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    tests = $totalTests
    failures = $totalFailures
    errors = $totalErrors
    skipped = $totalSkipped
    modules = @($moduleSummaries)
}

$outputDirectory = Split-Path -Parent $OutputPath
if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $OutputPath -Encoding utf8

Write-Host "Hosted Android instrumentation results accepted: tests=$totalTests skipped=$totalSkipped modules=$($moduleSummaries.Count)."
Write-Host "Evidence: $OutputPath"
