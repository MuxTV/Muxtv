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
$initialEmulatorProcessIds = @(
    Get-Process -ErrorAction SilentlyContinue |
        Where-Object { $_.ProcessName -ceq "emulator" -or $_.ProcessName -like "qemu-system-*" } |
        ForEach-Object Id
)

function Get-FreeBenchmarkEmulatorPort {
    for ($consolePort = 5680; $consolePort -ge 5554; $consolePort -= 2) {
        $listeners = @()
        try {
            foreach ($port in @($consolePort, ($consolePort + 1))) {
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

function Wait-BenchmarkAdbRegistration {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$TcpSerial,
        [Parameter(Mandatory)][System.Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 150
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $nextConnect = (Get-Date).AddSeconds(15)
    do {
        if ($Process.HasExited) {
            throw "Benchmark emulator exited before ADB registration (exitCode=$($Process.ExitCode))."
        }
        $state = & $Tools.Adb -s $Serial get-state 2>$null | Select-Object -First 1
        if ($LASTEXITCODE -eq 0 -and ([string]$state).Trim() -eq "device") { return }
        $tcpState = & $Tools.Adb -s $TcpSerial get-state 2>$null | Select-Object -First 1
        if ($LASTEXITCODE -eq 0 -and ([string]$tcpState).Trim() -eq "device") { return $TcpSerial }
        if ((Get-Date) -ge $nextConnect) {
            & $Tools.Adb connect $TcpSerial 2>&1 | Add-Content -Path (
                Join-Path $evidenceDirectory "adb-registration.log"
            ) -Encoding utf8
            $nextConnect = (Get-Date).AddSeconds(10)
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Benchmark emulator did not register with ADB as $Serial within $TimeoutSeconds seconds."
}

try {
    $tools = Get-AndroidSdkTools
    Test-AndroidAcceleration -Tools $tools -EvidenceDirectory $evidenceDirectory
    $image = Resolve-TvSystemImage -Tools $tools -PreferredApi 36
    $manifest.systemImage = $image.Package
    Install-AndroidPackage -Tools $tools -Package $image.Package -EvidenceDirectory $evidenceDirectory
    $consolePort = Get-FreeBenchmarkEmulatorPort
    $serial = "emulator-$consolePort"
    $tcpSerial = "127.0.0.1:$($consolePort + 1)"
    New-TvAvd `
        -Tools $tools `
        -Name "MuxTV_BENCHMARK_API36" `
        -SystemImagePackage $image.Package `
        -RamMb 2048 `
        -CpuCores 2
    & $tools.Adb kill-server 2>$null | Out-Null
    & $tools.Adb start-server 2>&1 | Add-Content -Path (Join-Path $evidenceDirectory "adb-server.log") -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw "Unable to start ADB before benchmark emulator startup." }
    $emulatorProcess = Start-TvEmulator `
        -Tools $tools `
        -AvdName "MuxTV_BENCHMARK_API36" `
        -Port $consolePort `
        -EvidenceDirectory $evidenceDirectory
    $registeredSerial = Wait-BenchmarkAdbRegistration `
        -Tools $tools `
        -Serial $serial `
        -TcpSerial $tcpSerial `
        -Process $emulatorProcess `
        -TimeoutSeconds 150
    if (-not [string]::IsNullOrWhiteSpace([string]$registeredSerial)) {
        $serial = [string]$registeredSerial
    }
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
        $createdProcesses = @(
            Get-Process -ErrorAction SilentlyContinue |
                Where-Object {
                    ($_.ProcessName -ceq "emulator" -or $_.ProcessName -like "qemu-system-*") -and
                    $_.Id -notin $initialEmulatorProcessIds
                }
        )
        foreach ($process in $createdProcesses) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
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
