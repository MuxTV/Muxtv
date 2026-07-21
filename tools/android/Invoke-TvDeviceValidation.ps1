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
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][System.Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 90
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ($Process.HasExited) {
            throw "Android TV emulator process exited before ADB became ready. Exit code: $($Process.ExitCode)."
        }
        if (Test-AdbDeviceReady -Tools $Tools -Serial $Serial) {
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "Android TV emulator $Serial did not become visible to ADB within $TimeoutSeconds seconds."
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
    Install-AndroidPackage -Tools $bootstrapTools -Package "platform-tools" -EvidenceDirectory $evidenceDirectory
    Install-AndroidPackage -Tools $bootstrapTools -Package "emulator" -EvidenceDirectory $evidenceDirectory

    $tools = Get-AndroidSdkTools
    Test-AndroidAcceleration -Tools $tools -EvidenceDirectory $evidenceDirectory

    $currentImage = Resolve-TvSystemImage -Tools $tools -PreferredApi 36
    $profiles = [System.Collections.Generic.List[object]]::new()

    if ($Mode -eq "DeviceMatrix") {
        $oldImage = Resolve-TvSystemImage -Tools $tools -PreferredApi 26 -AllowOldEdgeFallback
        $profiles.Add([pscustomobject]@{
            RequestedApi = 26
            Image = $oldImage
            AvdName = "MuxTV_TV_OLD_API$($oldImage.Api)"
            Port = 5554
            RamMb = 1536
            CpuCores = 2
            FallbackUsed = $oldImage.Api -ne 26
        })
    }

    $profiles.Add([pscustomobject]@{
        RequestedApi = 36
        Image = $currentImage
        AvdName = "MuxTV_TV_CURRENT_API$($currentImage.Api)"
        Port = if ($Mode -eq "DeviceMatrix") { 5556 } else { 5554 }
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
        $serial = "emulator-$($profile.Port)"
        $emulatorProcess = $null
        $profileRecord = [ordered]@{
            requestedApi = $profile.RequestedApi
            resolvedApi = $profile.Image.Api
            package = $profile.Image.Package
            flavor = $profile.Image.Flavor
            abi = $profile.Image.Abi
            avdName = $profile.AvdName
            serial = $serial
            port = $profile.Port
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

            & $tools.Adb -s $serial emu kill 2>$null | Out-Null
            $emulatorProcess = Start-TvEmulator `
                -Tools $tools `
                -AvdName $profile.AvdName `
                -Port $profile.Port `
                -EvidenceDirectory $profileDirectory

            Wait-AdbDevice -Tools $tools -Serial $serial -Process $emulatorProcess -TimeoutSeconds 90
            Wait-AndroidBoot -Tools $tools -Serial $serial -TimeoutSeconds 360
            $env:ANDROID_SERIAL = $serial
            Collect-AndroidEvidence -Tools $tools -Serial $serial -OutputDirectory $profileDirectory

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

            Write-Host "`n==> Device validation for Android TV API $($profile.Image.Api) ($serial)"
            & pwsh @validationArguments
            $validationExitCode = $LASTEXITCODE
            if ($validationExitCode -ne 0) {
                throw "Device validation failed with exit code $validationExitCode on $serial."
            }

            $profileRecord.status = "passed"
        } catch {
            $profileRecord.status = "failed"
            $profileRecord.failure = $_.Exception.Message
            throw
        } finally {
            $profileRecord.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")

            if (Test-AdbDeviceReady -Tools $tools -Serial $serial) {
                try {
                    Collect-AndroidEvidence -Tools $tools -Serial $serial -OutputDirectory $profileDirectory
                } catch {
                    Write-Warning "Unable to collect final emulator evidence for ${serial}: $($_.Exception.Message)"
                }
            }

            try {
                Stop-TvEmulator -Tools $tools -Serial $serial -Process $emulatorProcess
            } catch {
                Write-Warning "Unable to stop emulator cleanly for ${serial}: $($_.Exception.Message)"
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
