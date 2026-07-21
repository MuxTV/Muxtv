[CmdletBinding()]
param(
    [ValidateSet("DeviceCurrent", "DeviceMatrix")]
    [string]$Mode = "DeviceCurrent",

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

function Update-AndroidRuntimePackages {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$EvidenceDirectory
    )

    $logPath = Join-Path $EvidenceDirectory "sdkmanager-runtime-update.log"
    $accept = 1..200 | ForEach-Object { "y" }
    $accept | & $Tools.SdkManager "platform-tools" "emulator" 2>&1 |
        Tee-Object -FilePath $logPath
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to install or update platform-tools and emulator. See $logPath"
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
    $connectAfter = (Get-Date).AddSeconds(15)
    $nextConnect = $connectAfter

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
    startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    completedAtUtc = $null
    status = "running"
    requestedProfiles = @()
    profiles = @()
    failure = $null
}
Write-HarnessManifest -Manifest $manifest -Path $manifestPath

$previousAndroidSerial = $env:ANDROID_SERIAL

try {
    $bootstrapTools = Get-AndroidSdkTools -AllowMissingRuntime
    Update-AndroidRuntimePackages -Tools $bootstrapTools -EvidenceDirectory $evidenceDirectory

    $tools = Get-AndroidSdkTools
    Reset-AdbServer -Tools $tools -EvidenceDirectory $evidenceDirectory
    Test-AndroidAcceleration -Tools $tools -EvidenceDirectory $evidenceDirectory

    $currentImage = Resolve-TvSystemImage -Tools $tools -PreferredApi 36
    $profiles = [System.Collections.Generic.List[object]]::new()

    if ($Mode -eq "DeviceMatrix") {
        $oldImage = Resolve-TvSystemImage -Tools $tools -PreferredApi 26 -AllowOldEdgeFallback
        $profiles.Add([pscustomobject]@{
            RequestedApi = 26
            Image = $oldImage
            AvdName = "MuxTV_TV_OLD_API$($oldImage.Api)"
            RamMb = 1536
            CpuCores = 2
            FallbackUsed = $oldImage.Api -ne 26
        })
    }

    $profiles.Add([pscustomobject]@{
        RequestedApi = 36
        Image = $currentImage
        AvdName = "MuxTV_TV_CURRENT_API$($currentImage.Api)"
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

            Wait-AndroidBoot -Tools $tools -Serial $deviceSerial -TimeoutSeconds 360
            $env:ANDROID_SERIAL = $deviceSerial
            Collect-AndroidEvidence -Tools $tools -Serial $deviceSerial -OutputDirectory $profileDirectory

            $validationRoot = Join-Path $profileDirectory "validation"
            $validationArguments = @(
                "-NoProfile",
                "-File", $verifyScript,
                "-Mode", "Device",
                "-EvidenceRoot", $validationRoot,
                "-SourceBranch", $branch,
                "-SourceCommit", $commit
            )
            if ($NoDaemon) {
                $validationArguments += "-NoDaemon"
            }

            Write-Host "`n==> Device validation for Android TV API $($profile.Image.Api) ($deviceSerial)"
            & pwsh @validationArguments
            $validationExitCode = $LASTEXITCODE
            if ($validationExitCode -ne 0) {
                throw "Device validation failed with exit code $validationExitCode on $deviceSerial."
            }

            $profileRecord.status = "passed"
        } catch {
            $profileRecord.status = "failed"
            $profileRecord.failure = $_.Exception.Message
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
