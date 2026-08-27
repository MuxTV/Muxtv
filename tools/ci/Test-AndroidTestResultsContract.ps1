[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$assertScript = Join-Path $repositoryRoot "tools/ci/Assert-AndroidTestResults.ps1"
if (-not (Test-Path -LiteralPath $assertScript -PathType Leaf)) {
    throw "Hosted Android result assertion script is missing: $assertScript"
}

$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("muxtv-android-results-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $fixtureRoot | Out-Null

function Write-TestResult {
    param(
        [Parameter(Mandatory)][string]$Module,
        [Parameter(Mandatory)][int]$Tests,
        [int]$Failures = 0,
        [int]$Errors = 0,
        [int]$Skipped = 0
    )

    $directory = Join-Path $fixtureRoot (Join-Path $Module "build/outputs/androidTest-results/connected/debug")
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    @"
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="$Module" tests="$Tests" failures="$Failures" errors="$Errors" skipped="$Skipped" />
"@ | Set-Content -LiteralPath (Join-Path $directory "TEST-$($Module.Replace('/', '-')).xml") -Encoding utf8
}

function Assert-Throws {
    param(
        [Parameter(Mandatory)][scriptblock]$Action,
        [Parameter(Mandatory)][string]$ExpectedFragment
    )

    $message = ""
    try {
        & $Action
    } catch {
        $message = $_.Exception.Message
    }
    if ([string]::IsNullOrWhiteSpace($message)) {
        throw "Expected failure containing '$ExpectedFragment', but the action succeeded."
    }
    if ($message.IndexOf($ExpectedFragment, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "Expected failure containing '$ExpectedFragment', got: $message"
    }
}

try {
    Write-TestResult -Module "module-one" -Tests 3 -Skipped 1
    Write-TestResult -Module "module-two" -Tests 2
    $outputPath = Join-Path $fixtureRoot "evidence/results.json"

    & $assertScript `
        -RepositoryRoot $fixtureRoot `
        -ModulePaths @("module-one", "module-two") `
        -OutputPath $outputPath

    if (-not (Test-Path -LiteralPath $outputPath -PathType Leaf)) {
        throw "Hosted Android result assertion did not publish JSON evidence."
    }
    $summary = Get-Content -LiteralPath $outputPath -Raw | ConvertFrom-Json
    if ($summary.tests -ne 5 -or $summary.failures -ne 0 -or $summary.errors -ne 0 -or $summary.skipped -ne 1) {
        throw "Unexpected aggregate Android test summary: $($summary | ConvertTo-Json -Compress)"
    }

    $missingRoot = Join-Path $fixtureRoot "missing-fixture"
    New-Item -ItemType Directory -Force -Path $missingRoot | Out-Null
    Assert-Throws -ExpectedFragment "produced no TEST-*.xml" -Action {
        & $assertScript -RepositoryRoot $missingRoot -ModulePaths @("missing-module") -OutputPath (Join-Path $missingRoot "out.json")
    }

    $failedRoot = Join-Path $fixtureRoot "failed-fixture"
    New-Item -ItemType Directory -Force -Path $failedRoot | Out-Null
    $oldFixtureRoot = $fixtureRoot
    $fixtureRoot = $failedRoot
    Write-TestResult -Module "failed-module" -Tests 1 -Failures 1
    $fixtureRoot = $oldFixtureRoot
    Assert-Throws -ExpectedFragment "reports failures=1 errors=0" -Action {
        & $assertScript -RepositoryRoot $failedRoot -ModulePaths @("failed-module") -OutputPath (Join-Path $failedRoot "out.json")
    }

    Write-Host "Hosted Android instrumentation result contract passed."
} finally {
    Remove-Item -LiteralPath $fixtureRoot -Recurse -Force -ErrorAction SilentlyContinue
}
