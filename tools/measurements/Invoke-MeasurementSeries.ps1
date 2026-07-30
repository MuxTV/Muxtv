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

function Write-MeasurementSeriesRequest {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Family,
        [Parameter(Mandatory)][string]$OutputName,
        [Parameter(Mandatory)][object[]]$Runs,
        $AndroidProfile
    )

    $request = [ordered]@{
        schemaVersion = 1
        family = $Family
        outputName = $OutputName
        runs = @($Runs)
        androidProfile = $AndroidProfile
    }
    $request | ConvertTo-Json -Depth 8 | Set-Content -Path $Path -Encoding utf8
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
    } catch {
        Write-Warning "Unable to remove the temporary measurement AVD."
    }
}

$profile = Get-MuxTvMeasurementProfile -Id $ProfileId
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
    branch = $SourceBranch
    commit = $SourceCommit
    profileId = $ProfileId
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
    hostM3uIncluded = ($ProfileId -eq "current-normal")
    failureCode = $null
    failureType = $null
    failureCommand = $null
    failureLine = $null
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding utf8

$previousAndroidSerial = $env:ANDROID_SERIAL
try {
    Set-Location $repositoryRoot
    $gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
    if (-not (Test-Path $gradleWrapper -PathType Leaf)) {
        throw "Gradle wrapper was not found."
    }

    if ($ProfileId -eq "current-normal") {
        $m3uRuns = [System.Collections.Generic.List[object]]::new()
        for ($index = 1; $index -le $Repetitions; $index++) {
            $token = $index.ToString("00")
            $reportName = "m3u-host-$token.json"
            $reportPath = Join-Path $inputDirectory $reportName
            $logPath = Join-Path $seriesDirectory "m3u-host-$token.log"
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
                "-PmeasurementOutput=$reportPath"
            )
            if ($NoDaemon) {
                $arguments += "--no-daemon"
            }
            Invoke-MeasurementNativeChild `
                -FilePath $gradleWrapper `
                -Arguments $arguments `
                -LogPath $logPath `
                -FailureMessage "Host M3U measurement repetition failed."
            if (-not (Test-Path $reportPath -PathType Leaf) -or (Get-Item $reportPath).Length -le 0) {
                throw "Host M3U measurement report was not created."
            }
            $m3uRuns.Add([ordered]@{
                repetitionId = "host-$token"
                reportName = $reportName
            })
        }
        $m3uRequestPath = Join-Path $requestDirectory "m3u-host-request.json"
        Write-MeasurementSeriesRequest `
            -Path $m3uRequestPath `
            -Family "m3u-parse" `
            -OutputName "m3u-host-variance.json" `
            -Runs $m3uRuns.ToArray() `
            -AndroidProfile $null
        Invoke-MeasurementSeriesAnalysis `
            -RequestPath $m3uRequestPath `
            -InputDirectory $inputDirectory `
            -OutputDirectory $outputDirectory `
            -LogPath (Join-Path $seriesDirectory "m3u-host-analysis.log")
    }

    $tools = Get-AndroidSdkTools
    Test-AndroidAcceleration -Tools $tools -EvidenceDirectory $seriesDirectory
    $resolveArguments = @{
        Tools = $tools
        PreferredApi = $profile.RequestedApi
    }
    if ($profile.AllowOldEdgeFallback) {
        $resolveArguments.AllowOldEdgeFallback = $true
    }
    $image = Resolve-TvSystemImage @resolveArguments
    Install-AndroidPackage -Tools $tools -Package $image.Package -EvidenceDirectory $seriesDirectory
    $fallbackUsed = [int]$image.Api -ne [int]$profile.RequestedApi
    $manifest.resolvedImage = $image.Package
    $manifest.resolvedApi = [int]$image.Api
    $manifest.resolvedAbi = [string]$image.Abi
    $manifest.fallbackUsed = $fallbackUsed
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding utf8

    $runnerLabel = "self-hosted-$ProfileId-api$($image.Api)-$($image.Abi)"
    $roomRuns = [System.Collections.Generic.List[object]]::new()
    $playerRuns = [System.Collections.Generic.List[object]]::new()
    $roomMeasurementScript = Join-Path $repositoryRoot "tools\android\Invoke-CatalogDatabaseMeasurement.ps1"
    $playerMeasurementScript = Join-Path $repositoryRoot "tools\android\Invoke-PlayerProxyMeasurement.ps1"

    for ($index = 1; $index -le $Repetitions; $index++) {
        $token = $index.ToString("00")
        $repetitionDirectory = Join-Path $seriesDirectory "android-$token"
        New-Item -ItemType Directory -Force -Path $repetitionDirectory | Out-Null
        $port = Get-MeasurementFreeEmulatorPort
        $serial = "emulator-$port"
        $avdName = "MuxTV_VARIANCE_$($ProfileId.Replace('-', '_'))_${token}_API$($image.Api)"
        $process = $null
        try {
            New-TvAvd `
                -Tools $tools `
                -Name $avdName `
                -SystemImagePackage $image.Package `
                -RamMb $profile.RamMb `
                -CpuCores $profile.CpuCores
            $process = Start-TvEmulator `
                -Tools $tools `
                -AvdName $avdName `
                -Port $port `
                -EvidenceDirectory $repetitionDirectory
            Wait-AndroidBoot -Tools $tools -Serial $serial -TimeoutSeconds 360
            $env:ANDROID_SERIAL = $serial
            Collect-AndroidEvidence -Tools $tools -Serial $serial -OutputDirectory $repetitionDirectory

            $roomReportName = "room-$ProfileId-$token.json"
            $roomChildArguments = @(
                "-NoProfile",
                "-File", $roomMeasurementScript,
                "-SourceCommit", $SourceCommit,
                "-RunnerLabel", $runnerLabel,
                "-Warmups", "2",
                "-Iterations", "5",
                "-EntryCount", "10000",
                "-OutputName", $roomReportName,
                "-EvidenceDirectory", $repetitionDirectory
            )
            if ($NoDaemon) {
                $roomChildArguments += "-NoDaemon"
            }
            Invoke-MeasurementNativeChild `
                -FilePath "pwsh" `
                -Arguments $roomChildArguments `
                -LogPath (Join-Path $repetitionDirectory "room-child.log") `
                -FailureMessage "Android Room measurement repetition failed."
            $roomChildReport = Join-Path $repetitionDirectory $roomReportName
            if (-not (Test-Path $roomChildReport -PathType Leaf)) {
                throw "Android Room measurement report was not created."
            }
            Copy-Item -Path $roomChildReport -Destination (Join-Path $inputDirectory $roomReportName)
            $roomRuns.Add([ordered]@{
                repetitionId = "$ProfileId-$token"
                reportName = $roomReportName
            })

            $playerReportName = "player-$ProfileId-$token.json"
            $playerChildArguments = @(
                "-NoProfile",
                "-File", $playerMeasurementScript,
                "-SourceCommit", $SourceCommit,
                "-RunnerLabel", $runnerLabel,
                "-Warmups", "2",
                "-Samples", "10",
                "-OperationsPerSample", "1000",
                "-OutputName", $playerReportName,
                "-EvidenceDirectory", $repetitionDirectory
            )
            if ($NoDaemon) {
                $playerChildArguments += "-NoDaemon"
            }
            Invoke-MeasurementNativeChild `
                -FilePath "pwsh" `
                -Arguments $playerChildArguments `
                -LogPath (Join-Path $repetitionDirectory "player-child.log") `
                -FailureMessage "Android Player measurement repetition failed."
            $playerChildReport = Join-Path $repetitionDirectory $playerReportName
            if (-not (Test-Path $playerChildReport -PathType Leaf)) {
                throw "Android Player measurement report was not created."
            }
            Copy-Item -Path $playerChildReport -Destination (Join-Path $inputDirectory $playerReportName)
            $playerRuns.Add([ordered]@{
                repetitionId = "$ProfileId-$token"
                reportName = $playerReportName
            })
        } finally {
            try {
                Collect-AndroidEvidence -Tools $tools -Serial $serial -OutputDirectory $repetitionDirectory
            } catch {
                Write-Warning "Unable to collect final Android measurement evidence."
            }
            try {
                Stop-TvEmulator -Tools $tools -Serial $serial -Process $process
            } catch {
                Write-Warning "Unable to stop the Android measurement emulator cleanly."
                if ($null -ne $process) {
                    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                }
            }
            Remove-MeasurementAvd -Tools $tools -Name $avdName
            if ([string]::IsNullOrWhiteSpace($previousAndroidSerial)) {
                Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
            } else {
                $env:ANDROID_SERIAL = $previousAndroidSerial
            }
        }
    }

    $androidProfile = [ordered]@{
        requestedApiLevel = [int]$profile.RequestedApi
        systemImage = [string]$image.Package
        configuredRamMb = [int]$profile.RamMb
        configuredCpuCores = [int]$profile.CpuCores
        fallbackUsed = [bool]$fallbackUsed
    }
    $roomRequestPath = Join-Path $requestDirectory "room-$ProfileId-request.json"
    Write-MeasurementSeriesRequest `
        -Path $roomRequestPath `
        -Family "catalog-database" `
        -OutputName "room-$ProfileId-variance.json" `
        -Runs $roomRuns.ToArray() `
        -AndroidProfile $androidProfile
    Invoke-MeasurementSeriesAnalysis `
        -RequestPath $roomRequestPath `
        -InputDirectory $inputDirectory `
        -OutputDirectory $outputDirectory `
        -LogPath (Join-Path $seriesDirectory "room-analysis.log")

    $playerRequestPath = Join-Path $requestDirectory "player-$ProfileId-request.json"
    Write-MeasurementSeriesRequest `
        -Path $playerRequestPath `
        -Family "player-proxy" `
        -OutputName "player-$ProfileId-variance.json" `
        -Runs $playerRuns.ToArray() `
        -AndroidProfile $androidProfile
    Invoke-MeasurementSeriesAnalysis `
        -RequestPath $playerRequestPath `
        -InputDirectory $inputDirectory `
        -OutputDirectory $outputDirectory `
        -LogPath (Join-Path $seriesDirectory "player-analysis.log")

    $manifest.status = "passed"
    Write-Host "Measurement variance series passed."
    Write-Host "profile=$ProfileId"
    Write-Host "repetitions=$Repetitions"
    Write-Host "evidence=$([System.IO.Path]::GetFileName($seriesDirectory))"
} catch {
    $commandName = [string]$_.InvocationInfo.MyCommand.Name
    if ([string]::IsNullOrWhiteSpace($commandName)) {
        $commandName = "unknown"
    }
    $manifest.status = "failed"
    $manifest.failureCode = "measurement-series-failed"
    $manifest.failureType = $_.Exception.GetType().FullName
    $manifest.failureCommand = [System.IO.Path]::GetFileName($commandName)
    $manifest.failureLine = [int]$_.InvocationInfo.ScriptLineNumber
    Write-Host "Measurement variance series failed. See evidence."
    throw "Measurement variance series failed. See evidence."
} finally {
    if ([string]::IsNullOrWhiteSpace($previousAndroidSerial)) {
        Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_SERIAL = $previousAndroidSerial
    }
    $manifest.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding utf8
}
