[CmdletBinding()]
param(
    [string]$EvidenceRoot = ".work/evidence",
    [string]$SourceBranch = "",
    [string]$SourceCommit = "",
    [switch]$NoDaemon
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

. (Join-Path $PSScriptRoot "AndroidSdk.ps1")

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
Set-Location $repositoryRoot

$actualCommit = (& git rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw "Unable to resolve the checked-out commit." }
$commit = if ([string]::IsNullOrWhiteSpace($SourceCommit)) { $actualCommit } else { $SourceCommit.Trim() }
if ($commit -cne $actualCommit) {
    throw "Benchmark evidence commit mismatch: expected $commit, checked out $actualCommit."
}
$branch = if ([string]::IsNullOrWhiteSpace($SourceBranch)) {
    (& git branch --show-current).Trim()
} else {
    $SourceBranch.Trim()
}
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$evidenceBase = if ([System.IO.Path]::IsPathRooted($EvidenceRoot)) {
    $EvidenceRoot
} else {
    Join-Path $repositoryRoot $EvidenceRoot
}
$evidenceDirectory = Join-Path $evidenceBase "$timestamp-$($commit.Substring(0, 12))-benchmark-dry-run"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$manifestPath = Join-Path $evidenceDirectory "benchmark-dry-run-manifest.json"

$manifest = [ordered]@{
    schemaVersion = 1
    repository = "MuxTV/Muxtv"
    branch = $branch
    commit = $commit
    variant = "benchmarkRelease"
    dryRun = $true
    startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    completedAtUtc = $null
    status = "running"
    deviceSerial = $null
    systemImage = $null
    testCount = 0
    failure = $null
}
function Write-Manifest {
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding utf8
}
Write-Manifest

$tools = $null
$serial = $null
$emulatorProcess = $null
$previousAndroidSerial = $env:ANDROID_SERIAL

function Get-FreeBenchmarkEmulatorPort {
    for ($consolePort = 5554; $consolePort -le 5680; $consolePort += 2) {
        $listeners = @()
        try {
            foreach ($port in @($consolePort, $consolePort + 1)) {
                $listener = [System.Net.Sockets.TcpListener]::new(
                    [System.Net.IPAddress]::Loopback,
                    $port
                )
                $listener.Start()
                $listeners += $listener
            }
            return $consolePort
        } catch {
            # Try the next adjacent console/ADB port pair.
        } finally {
            foreach ($listener in $listeners) { $listener.Stop() }
        }
    }
    throw "No free Android Emulator console/ADB port pair was found."
}

try {
    $tools = Get-AndroidSdkTools
    Test-AndroidAcceleration -Tools $tools -EvidenceDirectory $evidenceDirectory
    $image = Resolve-TvSystemImage -Tools $tools -PreferredApi 36
    $manifest.systemImage = $image.Package
    Install-AndroidPackage -Tools $tools -Package $image.Package -EvidenceDirectory $evidenceDirectory
    $consolePort = Get-FreeBenchmarkEmulatorPort
    $serial = "emulator-$consolePort"
    New-TvAvd `
        -Tools $tools `
        -Name "MuxTV_BENCHMARK_API36" `
        -SystemImagePackage $image.Package `
        -RamMb 2048 `
        -CpuCores 2
    $emulatorProcess = Start-TvEmulator `
        -Tools $tools `
        -AvdName "MuxTV_BENCHMARK_API36" `
        -Port $consolePort `
        -EvidenceDirectory $evidenceDirectory
    Wait-AndroidBoot -Tools $tools -Serial $serial -TimeoutSeconds 360
    $env:ANDROID_SERIAL = $serial
    $manifest.deviceSerial = $serial
    Collect-AndroidEvidence -Tools $tools -Serial $serial -OutputDirectory $evidenceDirectory
    @(
        "physical_memory=$(& $tools.Adb -s $serial shell cat /proc/meminfo)",
        "storage=$(& $tools.Adb -s $serial shell df /data)",
        "display_size=$(& $tools.Adb -s $serial shell wm size)",
        "display_density=$(& $tools.Adb -s $serial shell wm density)",
        "battery=$(& $tools.Adb -s $serial shell dumpsys battery)"
    ) | Set-Content -Path (Join-Path $evidenceDirectory "benchmark-device-context.log") -Encoding utf8
    Write-Manifest

    $resultRoot = Join-Path $repositoryRoot "benchmark\macrobenchmark\build\outputs\androidTest-results"
    Remove-Item -Path $resultRoot -Recurse -Force -ErrorAction SilentlyContinue
    $arguments = @(
        ":benchmark:macrobenchmark:connectedBenchmarkReleaseAndroidTest",
        "--stacktrace",
        "--console=plain",
        "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dryRunMode.enable=true",
        "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark"
    )
    if ($NoDaemon) { $arguments += "--no-daemon" }
    Invoke-CheckedNative `
        -FilePath $gradleWrapper `
        -Arguments $arguments `
        -LogPath (Join-Path $evidenceDirectory "macrobenchmark-dry-run.log")

    $resultFiles = @(Get-ChildItem -Path $resultRoot -Recurse -File -Filter "TEST-*.xml")
    $testCount = 0
    foreach ($resultFile in $resultFiles) {
        [xml]$document = Get-Content -Path $resultFile.FullName -Raw
        foreach ($suite in @($document.SelectNodes("//testsuite"))) {
            if ($null -ne $suite.Attributes["tests"]) {
                $testCount += [int]$suite.Attributes["tests"].Value
            }
            $failures = if ($null -ne $suite.Attributes["failures"]) { [int]$suite.Attributes["failures"].Value } else { 0 }
            $errors = if ($null -ne $suite.Attributes["errors"]) { [int]$suite.Attributes["errors"].Value } else { 0 }
            if (($failures + $errors) -gt 0) {
                throw "Macrobenchmark XML reports failures=$failures errors=$errors."
            }
        }
    }
    if ($testCount -lt 1) { throw "Macrobenchmark dry-run executed zero tests." }
    $manifest.testCount = $testCount
    $manifest.status = "passed"
} catch {
    $manifest.status = "failed"
    $manifest.failure = $_.Exception.Message
    throw
} finally {
    if ($null -ne $tools) {
        try {
            if ($null -ne $manifest.deviceSerial -and $null -ne $serial) {
                Collect-AndroidEvidence -Tools $tools -Serial $serial -OutputDirectory $evidenceDirectory
            }
        } catch {
            Write-Warning "Unable to collect final benchmark evidence: $($_.Exception.Message)"
        }
        try {
            if ($null -ne $serial) {
                Stop-TvEmulator -Tools $tools -Serial $serial -Process $emulatorProcess
            }
        } catch {
            Write-Warning "Unable to stop benchmark emulator cleanly: $($_.Exception.Message)"
        }
    }
    if ([string]::IsNullOrWhiteSpace($previousAndroidSerial)) {
        Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_SERIAL = $previousAndroidSerial
    }
    $manifest.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    Write-Manifest
    Write-Host "Benchmark dry-run evidence: $evidenceDirectory"
}
