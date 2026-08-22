[CmdletBinding()]
param(
    [ValidateSet("DeviceCurrent", "DeviceMatrix")]
    [string]$Mode = "DeviceCurrent",

    [string]$EvidenceRoot = ".work/evidence",

    [string]$SourceBranch = "",

    [string]$SourceCommit = "",

    [switch]$NoDaemon,

    [switch]$SkipHostValidation
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

. (Join-Path $PSScriptRoot "AndroidSdk.ps1")

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$verifyScript = Join-Path $repositoryRoot "tools\verify-local.ps1"
if (-not (Test-Path $verifyScript -PathType Leaf)) {
    throw "MuxTV verification script was not found: $verifyScript"
}

function Get-GitValue {
    param([string[]]$Arguments, [string]$Fallback)

    try {
        $firstLine = & git @Arguments 2>$null | Select-Object -First 1
        if ($null -eq $firstLine) { return $Fallback }
        $value = ([string]$firstLine).Trim()
        if ([string]::IsNullOrWhiteSpace($value)) { return $Fallback }
        return $value
    } catch {
        return $Fallback
    }
}

function Get-ShortCommit {
    param([string]$Value)

    $trimmed = $Value.Trim()
    if ($trimmed.Length -le 12) { return $trimmed }
    return $trimmed.Substring(0, 12)
}

function Write-HarnessManifest {
    param(
        [Parameter(Mandatory)]$Manifest,
        [Parameter(Mandatory)][string]$Path
    )

    $Manifest | ConvertTo-Json -Depth 10 | Set-Content -Path $Path -Encoding utf8
}

function Ensure-AndroidRuntimePackages {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$EvidenceDirectory
    )

    $missingPackages = [System.Collections.Generic.List[string]]::new()
    if (-not (Test-Path -LiteralPath $Tools.Adb -PathType Leaf)) {
        $missingPackages.Add("platform-tools")
    }
    if (-not (Test-Path -LiteralPath $Tools.Emulator -PathType Leaf)) {
        $missingPackages.Add("emulator")
    }

    $logPath = Join-Path $EvidenceDirectory "sdkmanager-runtime-ensure.log"
    if ($missingPackages.Count -eq 0) {
        "Android runtime executables already exist; sdkmanager update/install skipped." |
            Set-Content -LiteralPath $logPath -Encoding utf8
        return
    }

    $packages = @($missingPackages)
    "Installing only missing Android runtime packages: $($packages -join ', ')" |
        Set-Content -LiteralPath $logPath -Encoding utf8
    $accept = 1..200 | ForEach-Object { "y" }
    $runtimeOutput = @($accept | & $Tools.SdkManager @packages 2>&1)
    $runtimeExitCode = $LASTEXITCODE
    $runtimeOutput | Add-Content -LiteralPath $logPath -Encoding utf8
    $runtimeOutput | ForEach-Object { Write-Host $_ }
    if ($runtimeExitCode -ne 0) {
        throw "Unable to install missing Android runtime package(s). See $logPath"
    }

    if (-not (Test-Path -LiteralPath $Tools.Adb -PathType Leaf) -or
        -not (Test-Path -LiteralPath $Tools.Emulator -PathType Leaf)) {
        throw "Android runtime package installation completed but required executables are still unavailable. See $logPath"
    }
}

function Reset-AdbServer {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$EvidenceDirectory
    )

    $logPath = Join-Path $EvidenceDirectory "adb-server.log"
    & $Tools.Adb version 2>&1 | Set-Content -Path $logPath -Encoding utf8
    & $Tools.Adb kill-server 2>&1 | Add-Content -Path $logPath -Encoding utf8
    Start-Sleep -Seconds 1
    & $Tools.Adb start-server 2>&1 | Add-Content -Path $logPath -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to start the selected ADB server. See $logPath"
    }
    & $Tools.Adb devices -l 2>&1 | Add-Content -Path $logPath -Encoding utf8
}

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
        $adbPort = $consolePort + 1
        if ((Test-TcpPortAvailable -Port $consolePort) -and
            (Test-TcpPortAvailable -Port $adbPort)) {
            return $consolePort
        }
    }

    throw "No free Android Emulator console/ADB port pair was found in 5554-5682."
}

function Test-AdbDeviceReady {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial
    )

    try {
        $state = & $Tools.Adb -s $Serial get-state 2>$null | Select-Object -First 1
        return $LASTEXITCODE -eq 0 -and ([string]$state).Trim() -eq "device"
    } catch {
        return $false
    }
}

function Wait-AdbDevice {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$ConsoleSerial,
        [Parameter(Mandatory)][string]$TcpSerial,
        [Parameter(Mandatory)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [int]$TimeoutSeconds = 150
    )

    $logPath = Join-Path $EvidenceDirectory "adb-registration.log"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $nextConnect = (Get-Date).AddSeconds(15)

    do {
        if ($Process.HasExited) {
            throw "Android TV emulator process exited before ADB became ready. Exit code: $($Process.ExitCode)."
        }

        & $Tools.Adb devices -l 2>&1 | Add-Content -Path $logPath -Encoding utf8
        if (Test-AdbDeviceReady -Tools $Tools -Serial $ConsoleSerial) {
            return $ConsoleSerial
        }
        if (Test-AdbDeviceReady -Tools $Tools -Serial $TcpSerial) {
            return $TcpSerial
        }

        if ((Get-Date) -ge $nextConnect) {
            & $Tools.Adb connect $TcpSerial 2>&1 | Add-Content -Path $logPath -Encoding utf8
            $nextConnect = (Get-Date).AddSeconds(10)
        }

        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "Android TV emulator did not register with ADB as $ConsoleSerial or $TcpSerial within $TimeoutSeconds seconds. See $logPath"
}

function Get-AdbFirstLine {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $lines = @(& $Tools.Adb -s $Serial @Arguments 2>$null)
    if ($LASTEXITCODE -ne 0 -or $lines.Count -eq 0) {
        return ""
    }
    return ([string]$lines[0]).Trim()
}

function Wait-AndroidSystemReady {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [int]$TimeoutSeconds = 360
    )

    $logPath = Join-Path $EvidenceDirectory "android-readiness.log"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $bootCompleted = ""
    do {
        $bootCompleted = Get-AdbFirstLine `
            -Tools $Tools `
            -Serial $Serial `
            -Arguments @("shell", "getprop", "sys.boot_completed")
        "boot=$bootCompleted" | Add-Content -Path $logPath -Encoding utf8
        if ($bootCompleted -eq "1") { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    if ($bootCompleted -ne "1") {
        throw "Android TV emulator $Serial did not complete Android boot within $TimeoutSeconds seconds. See $logPath"
    }

    $packageDeadline = (Get-Date).AddSeconds(90)
    $androidPackage = ""
    do {
        $androidPackage = Get-AdbFirstLine `
            -Tools $Tools `
            -Serial $Serial `
            -Arguments @("shell", "pm", "path", "android")
        "packageManager=$androidPackage" | Add-Content -Path $logPath -Encoding utf8
        if ($androidPackage -like "package:*") { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $packageDeadline)

    if ($androidPackage -notlike "package:*") {
        throw "Android package manager did not become ready on $Serial. See $logPath"
    }

    & $Tools.Adb -s $Serial shell input keyevent 82 | Out-Null
    & $Tools.Adb -s $Serial shell settings put global window_animation_scale 0 | Out-Null
    & $Tools.Adb -s $Serial shell settings put global transition_animation_scale 0 | Out-Null
    & $Tools.Adb -s $Serial shell settings put global animator_duration_scale 0 | Out-Null
    Write-Host "Android TV emulator $Serial completed boot and package-manager readiness."
}

Set-Location $repositoryRoot

$gitCommit = Get-GitValue -Arguments @("rev-parse", "--short=12", "HEAD") -Fallback "unknown"
$gitBranch = Get-GitValue -Arguments @("branch", "--show-current") -Fallback "unknown"
$commit = if ([string]::IsNullOrWhiteSpace($SourceCommit)) { $gitCommit } else { Get-ShortCommit $SourceCommit }
$branch = if ([string]::IsNullOrWhiteSpace($SourceBranch)) { $gitBranch } else { $SourceBranch.Trim() }
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$evidenceBase = if ([System.IO.Path]::IsPathRooted($EvidenceRoot)) {
    $EvidenceRoot
} else {
    Join-Path $repositoryRoot $EvidenceRoot
}
$evidenceDirectory = Join-Path $evidenceBase "$timestamp-$commit-$($Mode.ToLowerInvariant())"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$manifestPath = Join-Path $evidenceDirectory "tv-device-manifest.json"

$manifest = [ordered]@{
    schemaVersion = 1
    repository = "MuxTV/Muxtv"
    branch = $branch
    commit = $commit
    mode = $Mode
    hostValidationSkipped = [bool]$SkipHostValidation
    startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    completedAtUtc = $null
    status = "running"
    toolchainEvidence = "android-toolchain.json"
    requestedProfiles = @()
    profiles = @()
    failure = $null
    failureType = $null
    failureTrace = $null
}
Write-HarnessManifest -Manifest $manifest -Path $manifestPath

$previousAndroidSerial = $env:ANDROID_SERIAL

try {
    $bootstrapTools = Get-AndroidSdkTools -AllowMissingRuntime
    Ensure-AndroidRuntimePackages -Tools $bootstrapTools -EvidenceDirectory $evidenceDirectory
    $tools = Get-AndroidSdkTools
    Collect-AndroidToolchainEvidence -Tools $tools -EvidenceDirectory $evidenceDirectory

    if (-not $SkipHostValidation) {
        $hostValidationRoot = Join-Path $evidenceDirectory "host-validation"
        $hostValidationArguments = @(
            "-NoProfile",
            "-File", $verifyScript,
            "-Mode", "Full",
            "-EvidenceRoot", $hostValidationRoot,
            "-SourceBranch", $branch,
            "-SourceCommit", $commit
        )
        if ($NoDaemon) {
            $hostValidationArguments += "-NoDaemon"
        }

        Write-Host "`n==> Host validation before Android TV emulator startup"
        & pwsh @hostValidationArguments
        $hostValidationExitCode = $LASTEXITCODE
        if ($hostValidationExitCode -ne 0) {
            throw "Host validation failed before Android TV emulator startup with exit code $hostValidationExitCode."
        }
    } else {
        Write-Host "`n==> Host validation skipped by orchestrating CI lane"
    }

    Reset-AdbServer -Tools $tools -EvidenceDirectory $evidenceDirectory
    Test-AndroidAcceleration -Tools $tools -EvidenceDirectory $evidenceDirectory

    $currentImage = Resolve-TvSystemImage -Tools $tools -PreferredApi 36
    $profiles = [System.Collections.Generic.List[object]]::new()

    if ($Mode -eq "DeviceMatrix") {
        $oldImage = Resolve-TvSystemImage -Tools $tools -PreferredApi 26
        $profiles.Add([pscustomobject]@{
            RequestedApi = 26
            Image = $oldImage
            AvdName = Get-MuxTvCanonicalAvdName -Api 26
            RamMb = 1536
            CpuCores = 2
            FallbackUsed = $false
        })
    }

    $profiles.Add([pscustomobject]@{
        RequestedApi = 36
        Image = $currentImage
        AvdName = Get-MuxTvCanonicalAvdName -Api 36
        RamMb = 2048
        CpuCores = 2
        FallbackUsed = $false
    })

    $manifest.requestedProfiles = @(
        $profiles | ForEach-Object {
            [ordered]@{
                requestedApi = $_.RequestedApi
                resolvedApi = $_.Image.Api
                package = $_.Image.Package
                flavor = $_.Image.Flavor
                abi = $_.Image.Abi
                fallbackUsed = $_.FallbackUsed
            }
        }
    )
    Write-HarnessManifest -Manifest $manifest -Path $manifestPath

    foreach ($profile in $profiles) {
        $profileDirectory = Join-Path $evidenceDirectory "api-$($profile.Image.Api)-$($profile.Image.Flavor)-$($profile.Image.Abi)"
        New-Item -ItemType Directory -Force -Path $profileDirectory | Out-Null
        $consolePort = Get-FreeEmulatorPort
        $adbPort = $consolePort + 1
        $consoleSerial = "emulator-$consolePort"
        $tcpSerial = "127.0.0.1:$adbPort"
        $deviceSerial = $null
        $emulatorProcess = $null
        $profileRecord = [ordered]@{
            requestedApi = $profile.RequestedApi
            resolvedApi = $profile.Image.Api
            package = $profile.Image.Package
            flavor = $profile.Image.Flavor
            abi = $profile.Image.Abi
            avdName = $profile.AvdName
            consolePort = $consolePort
            adbPort = $adbPort
            consoleSerial = $consoleSerial
            deviceSerial = $null
            ramMb = $profile.RamMb
            cpuCores = $profile.CpuCores
            fallbackUsed = $profile.FallbackUsed
            startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
            completedAtUtc = $null
            status = "running"
            failure = $null
            failureType = $null
            failureTrace = $null
        }
        $manifest.profiles += $profileRecord
        Write-HarnessManifest -Manifest $manifest -Path $manifestPath

        try {
            Install-AndroidPackage -Tools $tools -Package $profile.Image.Package -EvidenceDirectory $profileDirectory
            New-TvAvd `
                -Tools $tools `
                -Name $profile.AvdName `
                -SystemImagePackage $profile.Image.Package `
                -RamMb $profile.RamMb `
                -CpuCores $profile.CpuCores

            Reset-AdbServer -Tools $tools -EvidenceDirectory $profileDirectory
            $emulatorProcess = Start-TvEmulator `
                -Tools $tools `
                -AvdName $profile.AvdName `
                -Port $consolePort `
                -EvidenceDirectory $profileDirectory

            $deviceSerial = Wait-AdbDevice `
                -Tools $tools `
                -ConsoleSerial $consoleSerial `
                -TcpSerial $tcpSerial `
                -Process $emulatorProcess `
                -EvidenceDirectory $profileDirectory `
                -TimeoutSeconds 150
            $profileRecord.deviceSerial = $deviceSerial
            Write-HarnessManifest -Manifest $manifest -Path $manifestPath

            Wait-AndroidSystemReady `
                -Tools $tools `
                -Serial $deviceSerial `
                -EvidenceDirectory $profileDirectory `
                -TimeoutSeconds 360
            $env:ANDROID_SERIAL = $deviceSerial
            Collect-AndroidEvidence -Tools $tools -Serial $deviceSerial -OutputDirectory $profileDirectory

            $validationRoot = Join-Path $profileDirectory "validation"
            $validationArguments = @(
                "-NoProfile",
                "-File", $verifyScript,
                "-Mode", "DeviceOnly",
                "-EvidenceRoot", $validationRoot,
                "-SourceBranch", $branch,
                "-SourceCommit", $commit
            )
            if ($NoDaemon) {
                $validationArguments += "-NoDaemon"
            }

            Write-Host "`n==> Connected device validation for Android TV API $($profile.Image.Api) ($deviceSerial)"
            & pwsh @validationArguments
            $validationExitCode = $LASTEXITCODE
            if ($validationExitCode -ne 0) {
                throw "Connected device validation failed with exit code $validationExitCode on $deviceSerial."
            }

            $profileRecord.status = "passed"
        } catch {
            $profileRecord.status = "failed"
            $profileRecord.failure = $_.Exception.Message
            $profileRecord.failureType = $_.Exception.GetType().FullName
            $profileRecord.failureTrace = $_.ScriptStackTrace
            throw
        } finally {
            $profileRecord.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")

            if ($null -ne $deviceSerial -and
                (Test-AdbDeviceReady -Tools $tools -Serial $deviceSerial)) {
                try {
                    Collect-AndroidEvidence -Tools $tools -Serial $deviceSerial -OutputDirectory $profileDirectory
                } catch {
                    Write-Warning "Unable to collect final emulator evidence for ${deviceSerial}: $($_.Exception.Message)"
                }
            }

            try {
                Stop-TvEmulator -Tools $tools -Serial $consoleSerial -Process $emulatorProcess
            } catch {
                Write-Warning "Unable to stop emulator cleanly for ${consoleSerial}: $($_.Exception.Message)"
                if ($null -ne $emulatorProcess) {
                    Stop-Process -Id $emulatorProcess.Id -Force -ErrorAction SilentlyContinue
                }
            }

            Write-HarnessManifest -Manifest $manifest -Path $manifestPath
        }
    }

    $manifest.status = "passed"
} catch {
    $manifest.status = "failed"
    $manifest.failure = $_.Exception.Message
    $manifest.failureType = $_.Exception.GetType().FullName
    $manifest.failureTrace = $_.ScriptStackTrace
    throw
} finally {
    if ([string]::IsNullOrWhiteSpace($previousAndroidSerial)) {
        Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_SERIAL = $previousAndroidSerial
    }

    $manifest.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    Write-HarnessManifest -Manifest $manifest -Path $manifestPath
    Write-Host "`nTV device evidence: $evidenceDirectory"
}
