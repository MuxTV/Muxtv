[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$SourceCommit,

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$')]
    [string]$SourceBranch = "local",

    [ValidateSet("current-normal", "old-edge-normal", "current-low-ram")]
    [string]$ProfileId = "current-normal",

    [ValidateRange(2, 20)]
    [int]$Repetitions = 2,

    [string]$EvidenceRoot = ".work/evidence",

    [switch]$NoDaemon
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$LASTEXITCODE = 0
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $PSScriptRoot "MeasurementProfiles.ps1")
. (Join-Path $repositoryRoot "tools\android\AndroidSdk.ps1")

function Test-MeasurementTcpPortAvailable {
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

function Get-MeasurementFreeEmulatorPort {
    for ($consolePort = 5554; $consolePort -le 5680; $consolePort += 2) {
        if ((Test-MeasurementTcpPortAvailable -Port $consolePort) -and
            (Test-MeasurementTcpPortAvailable -Port ($consolePort + 1))) {
            return $consolePort
        }
    }
    throw "No free Android Emulator console/ADB port pair was found."
}

function Invoke-MeasurementNativeChild {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$LogPath,
        [Parameter(Mandatory)][string]$FailureMessage
    )

    $output = @(& $FilePath @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $output | Tee-Object -FilePath $LogPath | ForEach-Object { Write-Host $_ }
    "exit_code=$exitCode" | Add-Content -Path $LogPath -Encoding utf8
    if ($exitCode -ne 0) {
        throw $FailureMessage
    }
}

function Stop-MeasurementGradleDaemons {
    param(
        [Parameter(Mandatory)][string]$GradleWrapper,
        [Parameter(Mandatory)][string]$LogPath
    )

    $output = @(& $GradleWrapper --stop 2>&1)
    $exitCode = $LASTEXITCODE
    $output | Add-Content -Path $LogPath -Encoding utf8
    "exit_code=$exitCode" | Add-Content -Path $LogPath -Encoding utf8
    if ($exitCode -ne 0) {
        throw "Gradle daemon handoff failed."
    }
}

function Write-MeasurementSeriesRequest {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Family,
        [Parameter(Mandatory)][string]$OutputName,
        [Parameter(Mandatory)][object[]]$Runs,
        $AndroidProfile
    )

    [ordered]@{
        schemaVersion = 1
        family = $Family
        outputName = $OutputName
        runs = @($Runs)
        androidProfile = $AndroidProfile
    } | ConvertTo-Json -Depth 8 | Set-Content -Path $Path -Encoding utf8
}

function Invoke-MeasurementSeriesAnalysis {
    param(
        [Parameter(Mandatory)][string]$RequestPath,
        [Parameter(Mandatory)][string]$InputDirectory,
        [Parameter(Mandatory)][string]$OutputDirectory,
        [Parameter(Mandatory)][string]$LogPath
    )

    $arguments = @(
        ":core:testing:analyzeMeasurementSeries",
        "--stacktrace",
        "--console=plain",
        "-PmeasurementSeriesRequest=$RequestPath",
        "-PmeasurementSeriesInputDirectory=$InputDirectory",
        "-PmeasurementSeriesOutputDirectory=$OutputDirectory"
    )
    if ($NoDaemon) {
        $arguments += "--no-daemon"
    }

    Invoke-MeasurementNativeChild `
        -FilePath (Join-Path $repositoryRoot "gradlew.bat") `
        -Arguments $arguments `
        -LogPath $LogPath `
        -FailureMessage "Measurement series analysis failed."
}

function Remove-MeasurementAvd {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Name
    )

    try {
        & $Tools.AvdManager delete avd --name $Name 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Unable to remove the temporary measurement AVD."
        }
    } catch {
        Write-Warning "Unable to remove the temporary measurement AVD."
    }
}

function Reset-MeasurementAdbServer {
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
        throw "Unable to start the repository-selected ADB server."
    }
    & $Tools.Adb devices -l 2>&1 | Add-Content -Path $logPath -Encoding utf8
}

function Test-MeasurementAdbDeviceReady {
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

function Wait-MeasurementAdbDevice {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$ConsoleSerial,
        [Parameter(Mandatory)][string]$TcpSerial,
        [Parameter(Mandatory)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [int]$TimeoutSeconds = 180
    )

    $logPath = Join-Path $EvidenceDirectory "adb-registration.log"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $nextConnect = Get-Date

    do {
        if ($Process.HasExited) {
            throw "Android TV emulator exited before ADB registration."
        }

        & $Tools.Adb devices -l 2>&1 | Add-Content -Path $logPath -Encoding utf8
        if (Test-MeasurementAdbDeviceReady -Tools $Tools -Serial $TcpSerial) {
            return $TcpSerial
        }
        if (Test-MeasurementAdbDeviceReady -Tools $Tools -Serial $ConsoleSerial) {
            return $ConsoleSerial
        }

        if ((Get-Date) -ge $nextConnect) {
            & $Tools.Adb connect $TcpSerial 2>&1 | Add-Content -Path $logPath -Encoding utf8
            $nextConnect = (Get-Date).AddSeconds(5)
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "Android TV emulator did not register with ADB within the configured timeout."
}

$profile = Get-MuxTvMeasurementProfile -Id $ProfileId
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
$catalogMeasurementScript = Join-Path $repositoryRoot "tools\android\Invoke-CatalogDatabaseMeasurement.ps1"
$playerMeasurementScript = Join-Path $repositoryRoot "tools\android\Invoke-PlayerProxyMeasurement.ps1"
foreach ($requiredFile in @($gradleWrapper, $catalogMeasurementScript, $playerMeasurementScript)) {
    if (-not (Test-Path $requiredFile -PathType Leaf)) {
        throw "A required repository measurement entry point is missing."
    }
}

$resolvedEvidenceRoot = if ([System.IO.Path]::IsPathRooted($EvidenceRoot)) {
    $EvidenceRoot
} else {
    Join-Path $repositoryRoot $EvidenceRoot
}
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$shortCommit = $SourceCommit.Substring(0, 12)
$seriesDirectory = Join-Path $resolvedEvidenceRoot "$timestamp-$shortCommit-$ProfileId-variance-series"
$inputDirectory = Join-Path $seriesDirectory "input"
$outputDirectory = Join-Path $seriesDirectory "output"
$requestDirectory = Join-Path $seriesDirectory "requests"
New-Item -ItemType Directory -Force -Path $inputDirectory, $outputDirectory, $requestDirectory | Out-Null
$manifestPath = Join-Path $seriesDirectory "measurement-series-run-manifest.json"

$manifest = [ordered]@{
    schemaVersion = 1
    repository = "MuxTV/Muxtv"
    branch = $SourceBranch.Trim()
    commit = $SourceCommit
    profileId = $profile.Id
    repetitions = $Repetitions
    status = "running"
    startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    completedAtUtc = $null
    requestedApi = $profile.RequestedApi
    resolvedImage = $null
    resolvedApi = $null
    resolvedAbi = $null
    fallbackUsed = $null
    configuredRamMb = $profile.RamMb
    configuredCpuCores = $profile.CpuCores
    hostM3uIncluded = $true
    failureCode = $null
    failureType = $null
    failureCommand = $null
    failureLine = $null
}
$manifest | ConvertTo-Json -Depth 10 | Set-Content -Path $manifestPath -Encoding utf8

$previousAndroidSerial = $env:ANDROID_SERIAL
$tools = $null
$image = $null
$emulatorProcess = $null
$consoleSerial = $null
$deviceSerial = $null
$avdName = $null

try {
    Set-Location $repositoryRoot

    $m3uRuns = [System.Collections.Generic.List[object]]::new()
    for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
        $suffix = $repetition.ToString("00")
        $outputName = "m3u-host-$suffix.json"
        $outputPath = Join-Path $inputDirectory $outputName
        $arguments = @(
            ":core:testing:measureM3uParse",
            "--stacktrace",
            "--console=plain",
            "-PmeasurementProfile=small-1k",
            "-PmeasurementSeed=20260728",
            "-PmeasurementSourceCommit=$SourceCommit",
            "-PmeasurementWarmups=2",
            "-PmeasurementIterations=5",
            "-PmeasurementRunnerLabel=self-hosted-windows-x64-v1",
            "-PmeasurementOutput=$outputPath"
        )
        if ($NoDaemon) {
            $arguments += "--no-daemon"
        }
        Invoke-MeasurementNativeChild `
            -FilePath $gradleWrapper `
            -Arguments $arguments `
            -LogPath (Join-Path $seriesDirectory "m3u-host-$suffix.log") `
            -FailureMessage "M3U measurement repetition failed."
        $m3uRuns.Add([ordered]@{
            repetitionId = "host-$suffix"
            reportName = $outputName
        })
    }

    $m3uRequestPath = Join-Path $requestDirectory "m3u-host-request.json"
    Write-MeasurementSeriesRequest `
        -Path $m3uRequestPath `
        -Family "m3u-parse" `
        -OutputName "m3u-host-variance.json" `
        -Runs @($m3uRuns) `
        -AndroidProfile $null
    Invoke-MeasurementSeriesAnalysis `
        -RequestPath $m3uRequestPath `
        -InputDirectory $inputDirectory `
        -OutputDirectory $outputDirectory `
        -LogPath (Join-Path $seriesDirectory "m3u-host-analysis.log")

    $tools = Get-AndroidSdkTools
    Test-AndroidAcceleration -Tools $tools -EvidenceDirectory $seriesDirectory
    $image = if ($profile.AllowOldEdgeFallback) {
        Resolve-TvSystemImage -Tools $tools -PreferredApi $profile.RequestedApi -AllowOldEdgeFallback
    } else {
        Resolve-TvSystemImage -Tools $tools -PreferredApi $profile.RequestedApi
    }
    Install-AndroidPackage -Tools $tools -Package $image.Package -EvidenceDirectory $seriesDirectory

    $fallbackUsed = $image.Api -ne $profile.RequestedApi
    $manifest.resolvedImage = $image.Package
    $manifest.resolvedApi = $image.Api
    $manifest.resolvedAbi = $image.Abi
    $manifest.fallbackUsed = $fallbackUsed
    $manifest | ConvertTo-Json -Depth 10 | Set-Content -Path $manifestPath -Encoding utf8

    $roomRuns = [System.Collections.Generic.List[object]]::new()
    $playerRuns = [System.Collections.Generic.List[object]]::new()
    for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
        $suffix = $repetition.ToString("00")
        $repetitionDirectory = Join-Path $seriesDirectory "android-$suffix"
        New-Item -ItemType Directory -Force -Path $repetitionDirectory | Out-Null

        $consolePort = Get-MeasurementFreeEmulatorPort
        $adbPort = $consolePort + 1
        $consoleSerial = "emulator-$consolePort"
        $tcpSerial = "127.0.0.1:$adbPort"
        $deviceSerial = $null
        $avdName = "MuxTV_VARIANCE_$($profile.Id.Replace('-', '_'))_${suffix}_API$($image.Api)"
        $emulatorProcess = $null

        try {
            New-TvAvd `
                -Tools $tools `
                -Name $avdName `
                -SystemImagePackage $image.Package `
                -RamMb $profile.RamMb `
                -CpuCores $profile.CpuCores
            Reset-MeasurementAdbServer -Tools $tools -EvidenceDirectory $repetitionDirectory
            $emulatorProcess = Start-TvEmulator `
                -Tools $tools `
                -AvdName $avdName `
                -Port $consolePort `
                -EvidenceDirectory $repetitionDirectory
            $deviceSerial = Wait-MeasurementAdbDevice `
                -Tools $tools `
                -ConsoleSerial $consoleSerial `
                -TcpSerial $tcpSerial `
                -Process $emulatorProcess `
                -EvidenceDirectory $repetitionDirectory `
                -TimeoutSeconds 180
            Wait-AndroidBoot -Tools $tools -Serial $deviceSerial -TimeoutSeconds 360
            $env:ANDROID_SERIAL = $deviceSerial
            Collect-AndroidEvidence -Tools $tools -Serial $deviceSerial -OutputDirectory $repetitionDirectory

            $runnerLabel = "self-hosted-android-tv-api$($image.Api)-$($image.Abi)"
            $roomOutputName = "catalog-database-$suffix.json"
            $playerOutputName = "player-proxy-$suffix.json"

            $roomArguments = @(
                "-NoProfile",
                "-File", $catalogMeasurementScript,
                "-SourceCommit", $SourceCommit,
                "-RunnerLabel", $runnerLabel,
                "-Warmups", "1",
                "-Iterations", "5",
                "-EntryCount", "50000",
                "-OutputName", $roomOutputName,
                "-EvidenceDirectory", $repetitionDirectory
            )
            if ($NoDaemon) {
                $roomArguments += "-NoDaemon"
            }
            Invoke-MeasurementNativeChild `
                -FilePath "pwsh" `
                -Arguments $roomArguments `
                -LogPath (Join-Path $repetitionDirectory "catalog-database-child.log") `
                -FailureMessage "Catalog database measurement repetition failed."

            if ($NoDaemon) {
                Stop-MeasurementGradleDaemons -GradleWrapper $gradleWrapper `
                    -LogPath (Join-Path $repetitionDirectory "gradle-daemon-handoff.log")
            }

            $playerArguments = @(
                "-NoProfile",
                "-File", $playerMeasurementScript,
                "-SourceCommit", $SourceCommit,
                "-RunnerLabel", $runnerLabel,
                "-Warmups", "2",
                "-Samples", "5",
                "-OperationsPerSample", "1000",
                "-OutputName", $playerOutputName,
                "-EvidenceDirectory", $repetitionDirectory
            )
            if ($NoDaemon) {
                $playerArguments += "-NoDaemon"
            }
            Invoke-MeasurementNativeChild `
                -FilePath "pwsh" `
                -Arguments $playerArguments `
                -LogPath (Join-Path $repetitionDirectory "player-proxy-child.log") `
                -FailureMessage "Player proxy measurement repetition failed."

            $roomSource = Join-Path $repetitionDirectory $roomOutputName
            $playerSource = Join-Path $repetitionDirectory $playerOutputName
            if (-not (Test-Path $roomSource -PathType Leaf) -or
                -not (Test-Path $playerSource -PathType Leaf)) {
                throw "An Android child measurement report is missing."
            }
            Copy-Item -LiteralPath $roomSource -Destination (Join-Path $inputDirectory $roomOutputName)
            Copy-Item -LiteralPath $playerSource -Destination (Join-Path $inputDirectory $playerOutputName)
            $roomRuns.Add([ordered]@{
                repetitionId = "room-$suffix"
                reportName = $roomOutputName
            })
            $playerRuns.Add([ordered]@{
                repetitionId = "player-$suffix"
                reportName = $playerOutputName
            })
        } finally {
            if ($null -ne $deviceSerial -and
                (Test-MeasurementAdbDeviceReady -Tools $tools -Serial $deviceSerial)) {
                try {
                    Collect-AndroidEvidence -Tools $tools -Serial $deviceSerial -OutputDirectory $repetitionDirectory
                } catch {
                    Write-Warning "Unable to collect final Android measurement evidence."
                }
            }
            try {
                Stop-TvEmulator -Tools $tools -Serial $consoleSerial -Process $emulatorProcess
            } catch {
                if ($null -ne $emulatorProcess) {
                    Stop-Process -Id $emulatorProcess.Id -Force -ErrorAction SilentlyContinue
                }
            }
            Remove-MeasurementAvd -Tools $tools -Name $avdName
            if ([string]::IsNullOrWhiteSpace($previousAndroidSerial)) {
                Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
            } else {
                $env:ANDROID_SERIAL = $previousAndroidSerial
            }
            $emulatorProcess = $null
            $deviceSerial = $null
            $consoleSerial = $null
            $avdName = $null
        }
    }

    $androidProfile = [ordered]@{
        requestedApiLevel = $profile.RequestedApi
        systemImage = $image.Package
        configuredRamMb = $profile.RamMb
        configuredCpuCores = $profile.CpuCores
        fallbackUsed = $fallbackUsed
    }

    $roomRequestPath = Join-Path $requestDirectory "catalog-database-request.json"
    Write-MeasurementSeriesRequest `
        -Path $roomRequestPath `
        -Family "catalog-database" `
        -OutputName "catalog-database-$ProfileId-variance.json" `
        -Runs @($roomRuns) `
        -AndroidProfile $androidProfile
    Invoke-MeasurementSeriesAnalysis `
        -RequestPath $roomRequestPath `
        -InputDirectory $inputDirectory `
        -OutputDirectory $outputDirectory `
        -LogPath (Join-Path $seriesDirectory "catalog-database-analysis.log")

    $playerRequestPath = Join-Path $requestDirectory "player-proxy-request.json"
    Write-MeasurementSeriesRequest `
        -Path $playerRequestPath `
        -Family "player-proxy" `
        -OutputName "player-proxy-$ProfileId-variance.json" `
        -Runs @($playerRuns) `
        -AndroidProfile $androidProfile
    Invoke-MeasurementSeriesAnalysis `
        -RequestPath $playerRequestPath `
        -InputDirectory $inputDirectory `
        -OutputDirectory $outputDirectory `
        -LogPath (Join-Path $seriesDirectory "player-proxy-analysis.log")

    $manifest.status = "passed"
    Write-Host "Measurement variance series passed."
    Write-Host "profile=$ProfileId"
    Write-Host "repetitions=$Repetitions"
    Write-Host "thresholdApplied=false"
} catch {
    $manifest.status = "failed"
    $manifest.failureCode = "measurement-series-failed"
    $manifest.failureType = $_.Exception.GetType().FullName
    $manifest.failureCommand = "Invoke-MeasurementSeries.ps1"
    $manifest.failureLine = [int]$_.InvocationInfo.ScriptLineNumber
    Write-Host "Measurement variance series failed. See evidence."
    throw "Measurement variance series failed. See evidence."
} finally {
    if ($null -ne $tools -and $null -ne $consoleSerial) {
        try {
            Stop-TvEmulator -Tools $tools -Serial $consoleSerial -Process $emulatorProcess
        } catch {
            if ($null -ne $emulatorProcess) {
                Stop-Process -Id $emulatorProcess.Id -Force -ErrorAction SilentlyContinue
            }
        }
    }
    if ($null -ne $tools -and -not [string]::IsNullOrWhiteSpace($avdName)) {
        Remove-MeasurementAvd -Tools $tools -Name $avdName
    }
    if ([string]::IsNullOrWhiteSpace($previousAndroidSerial)) {
        Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_SERIAL = $previousAndroidSerial
    }
    $manifest.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    $manifest | ConvertTo-Json -Depth 10 | Set-Content -Path $manifestPath -Encoding utf8
    Write-Host "Measurement series evidence: $seriesDirectory"
}
