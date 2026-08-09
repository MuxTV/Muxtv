[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$cleanupScript = Join-Path $PSScriptRoot "Reset-SelfHostedAndroidState.ps1"
if (-not (Test-Path -LiteralPath $cleanupScript -PathType Leaf)) {
    throw "Self-hosted Android cleanup script was not found."
}

$tokens = $null
$parseErrors = $null
$null = [System.Management.Automation.Language.Parser]::ParseFile(
    $cleanupScript,
    [ref]$tokens,
    [ref]$parseErrors
)
if (@($parseErrors).Count -gt 0) {
    throw "Self-hosted Android cleanup script is not valid PowerShell."
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("muxtv-runner-cleanup-" + [Guid]::NewGuid().ToString("N"))
try {
    $repositoryRoot = Join-Path $tempRoot "repository"
    $workEvidence = Join-Path $repositoryRoot ".work\evidence"
    $apkOutput = Join-Path $repositoryRoot "app\tv\build\outputs\apk"
    $testOutput = Join-Path $repositoryRoot "app\tv\build\outputs\androidTest-results"
    $testReport = Join-Path $repositoryRoot "app\tv\build\reports\androidTests"
    $screenshotOutput = Join-Path $repositoryRoot "app\tv\build\outputs\screenshots"
    $gradleCache = Join-Path $repositoryRoot ".gradle\caches"
    foreach ($path in @($workEvidence, $apkOutput, $testOutput, $testReport, $screenshotOutput, $gradleCache)) {
        New-Item -ItemType Directory -Force -Path $path | Out-Null
        Set-Content -LiteralPath (Join-Path $path "fixture.txt") -Value "fixture" -Encoding ascii
    }

    $adbCommands = [System.Collections.Generic.List[string]]::new()
    $processProbeCalls = [System.Collections.Generic.List[int]]::new()
    $emulatorProcesses = [System.Collections.Generic.List[object]]::new()
    $emulatorProcesses.Add([pscustomobject]@{ ProcessName = "emulator"; Id = 4242 })
    $stoppedProcessIds = [System.Collections.Generic.List[int]]::new()
    & $cleanupScript `
        -RepositoryRoot $repositoryRoot `
        -EmulatorProcessProbe { $processProbeCalls.Add(1); @($emulatorProcesses) } `
        -StopEmulatorProcess { param([object]$Process) $stoppedProcessIds.Add([int]$Process.Id); $null = $emulatorProcesses.Remove($Process) } `
        -AdbAction { param([string]$Command) $adbCommands.Add($Command) } `
        -BuildDirectoryProbe { param([string]$Root) @(Join-Path $Root "app\tv\build") }

    foreach ($removedPath in @($workEvidence, $apkOutput, $testOutput, $testReport, $screenshotOutput)) {
        if (Test-Path -LiteralPath $removedPath) {
            throw "Runner cleanup left repository-owned temporary output: $removedPath"
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $gradleCache "fixture.txt") -PathType Leaf)) {
        throw "Runner cleanup removed the Gradle dependency cache."
    }
    if ($processProbeCalls.Count -lt 2) {
        throw "Runner cleanup did not verify emulator process absence after cleanup."
    }
    if ([string]::Join(",", $stoppedProcessIds) -cne "4242") {
        throw "Runner cleanup did not stop the repository emulator process."
    }
    if ([string]::Join(",", $adbCommands) -cne "disconnect,kill-server") {
        throw "Runner cleanup did not reset ADB deterministically."
    }

    $outsideRoot = Join-Path $tempRoot "outside"
    New-Item -ItemType Directory -Force -Path $outsideRoot | Out-Null
    $escaped = $false
    try {
        & $cleanupScript `
            -RepositoryRoot $repositoryRoot `
            -AdditionalCleanupPath $outsideRoot `
            -EmulatorProcessProbe { @() } `
            -AdbAction { param([string]$Command) } `
            -BuildDirectoryProbe { param([string]$Root) @() }
    } catch {
        $escaped = $_.Exception.Message -match "outside the repository root"
    }
    if (-not $escaped) {
        throw "Runner cleanup accepted a target outside the repository root."
    }

    Write-Host "Self-hosted Android cleanup contract passed."
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
