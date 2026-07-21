Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-AndroidSdkRoot {
    [CmdletBinding()]
    param()

    $candidate = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        $env:ANDROID_SDK_ROOT
    } elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $env:ANDROID_HOME
    } else {
        throw "ANDROID_SDK_ROOT or ANDROID_HOME must be configured."
    }

    if (-not (Test-Path $candidate -PathType Container)) {
        throw "Android SDK directory does not exist: $candidate"
    }

    return (Resolve-Path $candidate).Path
}

function Get-FirstExistingFile {
    param([Parameter(Mandatory)][string[]]$Candidates)

    foreach ($candidate in $Candidates) {
        if (Test-Path $candidate -PathType Leaf) {
            return (Resolve-Path $candidate).Path
        }
    }

    return $null
}

function Get-AndroidSdkTools {
    [CmdletBinding()]
    param()

    $sdkRoot = Get-AndroidSdkRoot
    $cmdlineToolsRoot = Join-Path $sdkRoot "cmdline-tools"
    $cmdlineBins = [System.Collections.Generic.List[string]]::new()
    $cmdlineBins.Add((Join-Path $cmdlineToolsRoot "latest\bin"))
    if (Test-Path $cmdlineToolsRoot -PathType Container) {
        Get-ChildItem $cmdlineToolsRoot -Directory |
            Sort-Object Name -Descending |
            ForEach-Object { $cmdlineBins.Add((Join-Path $_.FullName "bin")) }
    }

    $sdkManager = Get-FirstExistingFile -Candidates @(
        $cmdlineBins | ForEach-Object { Join-Path $_ "sdkmanager.bat" }
    )
    $avdManager = Get-FirstExistingFile -Candidates @(
        $cmdlineBins | ForEach-Object { Join-Path $_ "avdmanager.bat" }
    )
    $emulator = Get-FirstExistingFile -Candidates @(
        (Join-Path $sdkRoot "emulator\emulator.exe")
    )
    $adb = Get-FirstExistingFile -Candidates @(
        (Join-Path $sdkRoot "platform-tools\adb.exe")
    )

    $missing = [System.Collections.Generic.List[string]]::new()
    if ($null -eq $sdkManager) { $missing.Add("sdkmanager.bat") }
    if ($null -eq $avdManager) { $missing.Add("avdmanager.bat") }
    if ($null -eq $emulator) { $missing.Add("emulator.exe") }
    if ($null -eq $adb) { $missing.Add("adb.exe") }
    if ($missing.Count -gt 0) {
        throw "Android SDK tools are missing: $($missing -join ', '). Install Android command-line tools, platform-tools and emulator."
    }

    return [pscustomobject]@{
        Root = $sdkRoot
        SdkManager = $sdkManager
        AvdManager = $avdManager
        Emulator = $emulator
        Adb = $adb
    }
}

function Invoke-CheckedNative {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [string]$LogPath = ""
    )

    Write-Host "`n> $FilePath $($Arguments -join ' ')"
    if ([string]::IsNullOrWhiteSpace($LogPath)) {
        & $FilePath @Arguments
    } else {
        $parent = Split-Path $LogPath -Parent
        if (-not [string]::IsNullOrWhiteSpace($parent)) {
            New-Item -ItemType Directory -Force -Path $parent | Out-Null
        }
        & $FilePath @Arguments 2>&1 | Tee-Object -FilePath $LogPath
    }

    if ($LASTEXITCODE -ne 0) {
        throw "Native command failed with exit code $LASTEXITCODE: $FilePath $($Arguments -join ' ')"
    }
}

function Get-AvailableTvSystemImages {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Tools)

    $lines = & $Tools.SdkManager --list 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "sdkmanager --list failed with exit code $LASTEXITCODE."
    }

    $seen = @{}
    $images = foreach ($line in $lines) {
        $text = [string]$line
        if ($text -match '(system-images;android-(\d+);(android-tv|google-tv);(x86_64|x86))') {
            $package = $Matches[1]
            if (-not $seen.ContainsKey($package)) {
                $seen[$package] = $true
                [pscustomobject]@{
                    Package = $package
                    Api = [int]$Matches[2]
                    Flavor = $Matches[3]
                    Abi = $Matches[4]
                }
            }
        }
    }

    return @($images)
}

function Resolve-TvSystemImage {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][int]$PreferredApi,
        [switch]$AllowOldEdgeFallback
    )

    $images = Get-AvailableTvSystemImages -Tools $Tools
    if ($images.Count -eq 0) {
        throw "No Android TV or Google TV system images were reported by sdkmanager."
    }

    $flavorRank = @{ "android-tv" = 0; "google-tv" = 1 }
    $abiRank = @{ "x86_64" = 0; "x86" = 1 }

    $exact = @($images |
        Where-Object Api -eq $PreferredApi |
        Sort-Object @{ Expression = { $flavorRank[$_.Flavor] } }, @{ Expression = { $abiRank[$_.Abi] } })
    if ($exact.Count -gt 0) {
        return $exact[0]
    }

    if ($AllowOldEdgeFallback) {
        $fallback = @($images |
            Where-Object { $_.Api -ge 26 -and $_.Api -le 30 } |
            Sort-Object Api, @{ Expression = { $flavorRank[$_.Flavor] } }, @{ Expression = { $abiRank[$_.Abi] } })
        if ($fallback.Count -gt 0) {
            Write-Warning "Android TV API $PreferredApi image is unavailable. Using the nearest available old-edge image: API $($fallback[0].Api), $($fallback[0].Package)."
            return $fallback[0]
        }
    }

    $available = $images |
        Sort-Object Api, Flavor, Abi |
        ForEach-Object { $_.Package }
    throw "Required Android TV API $PreferredApi image is unavailable. Available TV images: $($available -join ', ')"
}

function Install-AndroidPackage {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Package,
        [Parameter(Mandatory)][string]$EvidenceDirectory
    )

    $installed = & $Tools.SdkManager --list_installed 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "sdkmanager --list_installed failed with exit code $LASTEXITCODE."
    }
    if (($installed -join "`n") -match [regex]::Escape($Package)) {
        Write-Host "Android package already installed: $Package"
        return
    }

    $logPath = Join-Path $EvidenceDirectory "sdkmanager-install.log"
    Write-Host "Installing Android package: $Package"
    $accept = 1..200 | ForEach-Object { "y" }
    $accept | & $Tools.SdkManager $Package 2>&1 | Tee-Object -FilePath $logPath
    if ($LASTEXITCODE -ne 0) {
        throw "sdkmanager failed to install $Package. See $logPath"
    }
}

function Set-AvdConfigValue {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Value
    )

    $lines = if (Test-Path $Path -PathType Leaf) { @(Get-Content $Path) } else { @() }
    $pattern = '^' + [regex]::Escape($Name) + '='
    $replaced = $false
    $updated = foreach ($line in $lines) {
        if ($line -match $pattern) {
            $replaced = $true
            "$Name=$Value"
        } else {
            $line
        }
    }
    if (-not $replaced) {
        $updated += "$Name=$Value"
    }
    $updated | Set-Content -Path $Path -Encoding utf8
}

function New-TvAvd {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$SystemImagePackage,
        [int]$RamMb = 2048,
        [int]$CpuCores = 2
    )

    Write-Host "Creating deterministic AVD $Name from $SystemImagePackage"
    "no" | & $Tools.AvdManager create avd --force --name $Name --package $SystemImagePackage --device tv_1080p
    if ($LASTEXITCODE -ne 0) {
        throw "avdmanager failed to create $Name."
    }

    $avdHome = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_AVD_HOME)) {
        $env:ANDROID_AVD_HOME
    } else {
        Join-Path $env:USERPROFILE ".android\avd"
    }
    $configPath = Join-Path $avdHome "$Name.avd\config.ini"
    if (-not (Test-Path $configPath -PathType Leaf)) {
        throw "AVD config was not created: $configPath"
    }

    Set-AvdConfigValue -Path $configPath -Name "hw.cpu.ncore" -Value ([string]$CpuCores)
    Set-AvdConfigValue -Path $configPath -Name "hw.ramSize" -Value ([string]$RamMb)
    Set-AvdConfigValue -Path $configPath -Name "vm.heapSize" -Value "256"
    Set-AvdConfigValue -Path $configPath -Name "disk.dataPartition.size" -Value "4G"
    Set-AvdConfigValue -Path $configPath -Name "hw.keyboard" -Value "yes"
    Set-AvdConfigValue -Path $configPath -Name "hw.dPad" -Value "yes"
    Set-AvdConfigValue -Path $configPath -Name "hw.gpu.enabled" -Value "yes"
    Set-AvdConfigValue -Path $configPath -Name "showDeviceFrame" -Value "no"
    Set-AvdConfigValue -Path $configPath -Name "fastboot.forceColdBoot" -Value "yes"
    Set-AvdConfigValue -Path $configPath -Name "fastboot.forceFastBoot" -Value "no"
}

function Start-TvEmulator {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$AvdName,
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$EvidenceDirectory
    )

    $stdout = Join-Path $EvidenceDirectory "emulator-stdout.log"
    $stderr = Join-Path $EvidenceDirectory "emulator-stderr.log"
    $arguments = @(
        "@$AvdName",
        "-port", [string]$Port,
        "-no-window",
        "-no-audio",
        "-no-boot-anim",
        "-no-snapshot",
        "-wipe-data",
        "-gpu", "swiftshader_indirect",
        "-accel", "on",
        "-camera-back", "none",
        "-camera-front", "none",
        "-netdelay", "none",
        "-netspeed", "full"
    )

    Write-Host "Starting headless Android TV emulator: $AvdName on port $Port"
    return Start-Process -FilePath $Tools.Emulator `
        -ArgumentList $arguments `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru
}

function Wait-AndroidBoot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial,
        [int]$TimeoutSeconds = 300
    )

    & $Tools.Adb -s $Serial wait-for-device
    if ($LASTEXITCODE -ne 0) {
        throw "ADB could not find $Serial."
    }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Seconds 2
        $completed = (& $Tools.Adb -s $Serial shell getprop sys.boot_completed 2>$null | Select-Object -First 1)
        if (([string]$completed).Trim() -eq "1") {
            break
        }
    } while ((Get-Date) -lt $deadline)

    if (([string]$completed).Trim() -ne "1") {
        throw "Android TV emulator $Serial did not complete boot within $TimeoutSeconds seconds."
    }

    & $Tools.Adb -s $Serial shell input keyevent 82 | Out-Null
    & $Tools.Adb -s $Serial shell settings put global window_animation_scale 0 | Out-Null
    & $Tools.Adb -s $Serial shell settings put global transition_animation_scale 0 | Out-Null
    & $Tools.Adb -s $Serial shell settings put global animator_duration_scale 0 | Out-Null
    Write-Host "Android TV emulator $Serial completed boot."
}

function Collect-AndroidEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$OutputDirectory
    )

    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    & $Tools.Adb devices -l 2>&1 | Set-Content (Join-Path $OutputDirectory "adb-devices.log") -Encoding utf8

    $properties = @(
        "serial=$Serial",
        "sdk=$(& $Tools.Adb -s $Serial shell getprop ro.build.version.sdk)",
        "release=$(& $Tools.Adb -s $Serial shell getprop ro.build.version.release)",
        "abi=$(& $Tools.Adb -s $Serial shell getprop ro.product.cpu.abi)",
        "product=$(& $Tools.Adb -s $Serial shell getprop ro.product.name)",
        "model=$(& $Tools.Adb -s $Serial shell getprop ro.product.model)",
        "fingerprint=$(& $Tools.Adb -s $Serial shell getprop ro.build.fingerprint)"
    )
    $properties | Set-Content (Join-Path $OutputDirectory "device-properties.log") -Encoding utf8

    & $Tools.Adb -s $Serial logcat -d -v threadtime 2>&1 |
        Set-Content (Join-Path $OutputDirectory "logcat.txt") -Encoding utf8
    & $Tools.Adb -s $Serial shell dumpsys activity activities 2>&1 |
        Set-Content (Join-Path $OutputDirectory "activity.txt") -Encoding utf8
    & $Tools.Adb -s $Serial shell dumpsys meminfo app.muxtv.tv.debug 2>&1 |
        Set-Content (Join-Path $OutputDirectory "meminfo.txt") -Encoding utf8

    & $Tools.Adb -s $Serial shell screencap -p /sdcard/muxtv-ci.png 2>$null | Out-Null
    & $Tools.Adb -s $Serial pull /sdcard/muxtv-ci.png (Join-Path $OutputDirectory "screen.png") 2>$null | Out-Null
    & $Tools.Adb -s $Serial shell rm /sdcard/muxtv-ci.png 2>$null | Out-Null
}

function Stop-TvEmulator {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial,
        [System.Diagnostics.Process]$Process
    )

    Write-Host "Stopping Android TV emulator $Serial"
    & $Tools.Adb -s $Serial emu kill 2>$null | Out-Null
    if ($null -ne $Process) {
        if (-not $Process.WaitForExit(15000)) {
            Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

Export-ModuleMember -Function *
