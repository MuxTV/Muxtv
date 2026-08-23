[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$SourceCommit,

    [string]$SourceBranch = "local",

    [string]$EvidenceRoot = ".work/evidence",

    [ValidateRange(1024, 8192)]
    [int]$RamMb = 2048,

    [ValidateRange(1, 8)]
    [int]$CpuCores = 2,

    [switch]$NoDaemon
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$LASTEXITCODE = 0
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

. (Join-Path $PSScriptRoot "AndroidSdk.ps1")

function Test-TcpPortAvailable {
    param([Parameter(Mandatory)][int]$Port)

    $listener = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new(
            [System.Net.IPAddress]::Loopback,
            $Port
        )
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        if ($null -ne $listener) {
            $listener.Stop()
        }
    }
}

function Get-FreeEmulatorPort {
    for ($consolePort = 5554; $consolePort -le 5680; $consolePort += 2) {
        if ((Test-TcpPortAvailable -Port $consolePort) -and
            (Test-TcpPortAvailable -Port ($consolePort + 1))) {
            return $consolePort
        }
    }
    throw "No free Android Emulator console/ADB port pair was found."
}

function Wait-PlayerMeasurementAndroidReady {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [int]$TimeoutSeconds = 360
    )

    $logPath = Join-Path $EvidenceDirectory "android-readiness.log"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $bootCompleted = ""
    do {
        if ($Process.HasExited) {
            throw "Android TV emulator exited before system readiness."
        }
        $candidate = (& $Tools.Adb -s $Serial shell getprop sys.boot_completed 2>$null | Select-Object -First 1)
        $bootCompleted = if ($null -eq $candidate) { "" } else { ([string]$candidate).Trim() }
        "boot=$bootCompleted" | Add-Content -Path $logPath -Encoding utf8
        if ($bootCompleted -eq "1") { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    if ($bootCompleted -ne "1") {
        throw "Android TV emulator did not complete boot within the configured timeout."
    }

    $packageDeadline = (Get-Date).AddSeconds(90)
    $androidPackage = ""
    do {
        if ($Process.HasExited) {
            throw "Android TV emulator exited before package-manager readiness."
        }
        $candidate = (& $Tools.Adb -s $Serial shell pm path android 2>$null | Select-Object -First 1)
        $androidPackage = if ($null -eq $candidate) { "" } else { ([string]$candidate).Trim() }
        "packageManager=$androidPackage" | Add-Content -Path $logPath -Encoding utf8
        if ($androidPackage.StartsWith("package:", [System.StringComparison]::Ordinal)) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $packageDeadline)
    if (-not $androidPackage.StartsWith("package:", [System.StringComparison]::Ordinal)) {
        throw "Android package manager did not become ready within the configured timeout."
    }

    & $Tools.Adb -s $Serial shell input keyevent 82 | Out-Null
    & $Tools.Adb -s $Serial shell settings put global window_animation_scale 0 | Out-Null
    & $Tools.Adb -s $Serial shell settings put global transition_animation_scale 0 | Out-Null
    & $Tools.Adb -s $Serial shell settings put global animator_duration_scale 0 | Out-Null
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$measurementScript = Join-Path $PSScriptRoot "Invoke-PlayerProxyMeasurement.ps1"
if (-not (Test-Path $measurementScript -PathType Leaf)) {
    throw "Player proxy measurement script was not found."
}
$resolvedEvidenceRoot = if ([System.IO.Path]::IsPathRooted($EvidenceRoot)) {
    $EvidenceRoot
} else {
    Join-Path $repositoryRoot $EvidenceRoot
}
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$shortCommit = $SourceCommit.Substring(0, 12)
$evidenceDirectory = Join-Path $resolvedEvidenceRoot "$timestamp-$shortCommit-player-proxy-measurement"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$manifestPath = Join-Path $evidenceDirectory "player-proxy-device-manifest.json"

$manifest = [ordered]@{
    schemaVersion = 1
    repository = "MuxTV/Muxtv"
    branch = $SourceBranch.Trim()
    commit = $SourceCommit
    mode = "PlayerMeasurement"
    startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    completedAtUtc = $null
    status = "running"
    image = $null
    serial = $null
    ramMb = $RamMb
    cpuCores = $CpuCores
    failureCode = $null
    failureType = $null
    failureCommand = $null
    failureLine = $null
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding utf8

$previousAndroidSerial = $env:ANDROID_SERIAL
$tools = $null
$process = $null
$serial = $null
try {
    $tools = Get-AndroidSdkTools
    Test-AndroidAcceleration -Tools $tools -EvidenceDirectory $evidenceDirectory
    $image = Resolve-TvSystemImage -Tools $tools -PreferredApi 36
    Install-AndroidPackage -Tools $tools -Package $image.Package -EvidenceDirectory $evidenceDirectory

    $port = Get-FreeEmulatorPort
    $serial = "emulator-$port"
    $avdName = Get-MuxTvCanonicalAvdName -Api $image.Api
    $manifest.image = [ordered]@{
        api = $image.Api
        package = $image.Package
        flavor = $image.Flavor
        abi = $image.Abi
        fallbackUsed = $false
    }
    $manifest.serial = $serial
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding utf8

    New-TvAvd `
        -Tools $tools `
        -Name $avdName `
        -SystemImagePackage $image.Package `
        -RamMb $RamMb `
        -CpuCores $CpuCores
    $process = Start-TvEmulator `
        -Tools $tools `
        -AvdName $avdName `
        -Port $port `
        -EvidenceDirectory $evidenceDirectory
    Wait-PlayerMeasurementAndroidReady `
        -Tools $tools `
        -Serial $serial `
        -Process $process `
        -EvidenceDirectory $evidenceDirectory `
        -TimeoutSeconds 360
    $env:ANDROID_SERIAL = $serial
    Collect-AndroidEvidence -Tools $tools -Serial $serial -OutputDirectory $evidenceDirectory

    $measurementArguments = @(
        "-NoProfile",
        "-File", $measurementScript,
        "-SourceCommit", $SourceCommit,
        "-RunnerLabel", "self-hosted-android-tv-api$($image.Api)-$($image.Abi)",
        "-Warmups", "2",
        "-Samples", "10",
        "-OperationsPerSample", "1000",
        "-OutputName", "player-proxy-measurement.json",
        "-EvidenceDirectory", $evidenceDirectory
    )
    if ($NoDaemon) {
        $measurementArguments += "-NoDaemon"
    }

    $childLogPath = Join-Path $evidenceDirectory "player-proxy-measurement-child.log"
    $childOutput = & pwsh @measurementArguments 2>&1
    $childExitCode = $LASTEXITCODE
    $childOutput |
        Tee-Object -FilePath $childLogPath |
        ForEach-Object { Write-Host $_ }
    "exit_code=$childExitCode" | Add-Content -Path $childLogPath -Encoding utf8
    if ($childExitCode -ne 0) {
        throw "Player proxy device measurement failed with exit code $childExitCode."
    }

    $manifest.status = "passed"
} catch {
    $commandName = [string]$_.InvocationInfo.MyCommand.Name
    if ([string]::IsNullOrWhiteSpace($commandName)) {
        $commandName = "unknown"
    }
    $manifest.status = "failed"
    $manifest.failureCode = "player-proxy-device-validation-failed"
    $manifest.failureType = $_.Exception.GetType().FullName
    $manifest.failureCommand = [System.IO.Path]::GetFileName($commandName)
    $manifest.failureLine = [int]$_.InvocationInfo.ScriptLineNumber
    Write-Host "Player proxy device validation failed. See evidence."
    throw "Player proxy device validation failed. See evidence."
} finally {
    $manifest.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    if ($null -ne $tools -and $null -ne $serial) {
        try {
            Collect-AndroidEvidence -Tools $tools -Serial $serial -OutputDirectory $evidenceDirectory
        } catch {
            Write-Warning "Unable to collect final Player proxy device evidence."
        }
        try {
            Stop-TvEmulator -Tools $tools -Serial $serial -Process $process
        } catch {
            Write-Warning "Unable to stop Player measurement emulator cleanly."
            if ($null -ne $process) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($previousAndroidSerial)) {
        Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_SERIAL = $previousAndroidSerial
    }
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding utf8
    Write-Host "Player proxy device evidence: $evidenceDirectory"
}
