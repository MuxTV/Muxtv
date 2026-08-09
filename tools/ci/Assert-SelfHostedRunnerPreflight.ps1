[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path,
    [string]$EvidencePath = (Join-Path $RepositoryRoot ".work\evidence\runner-preflight\runner-preflight.json"),
    [ValidateRange(0, 1024)][double]$MinimumFreeDiskGb = 20,
    [ValidateRange(0, 1024)][double]$MinimumPhysicalMemoryGb = 4,
    [switch]$SkipAndroidToolchain,
    [switch]$RequireNoConnectedDevice,
    [string[]]$ExpectedRunnerLabels = @(),
    [ValidateRange(1, 99)][int[]]$ExpectedSystemImageApis = @(),
    [scriptblock]$RunnerMetadataProbe = {
        $version = $null
        if ($env:GITHUB_ACTIONS -ceq "true") {
            if (-not $env:RUNNER_TEMP) {
                throw "RUNNER_TEMP is unavailable."
            }
            $runnerRoot = Split-Path (Split-Path $env:RUNNER_TEMP -Parent) -Parent
            $listener = Join-Path $runnerRoot "bin\Runner.Listener.exe"
            if (-not (Test-Path -LiteralPath $listener -PathType Leaf)) {
                throw "Runner.Listener.exe is unavailable."
            }
            $version = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($listener).ProductVersion
        } else {
            $version = "local-pwsh-$($PSVersionTable.PSVersion)"
        }
        [pscustomobject]@{
            Name = if ($env:RUNNER_NAME) { $env:RUNNER_NAME } else { "local-contract" }
            Os = if ($env:RUNNER_OS) { $env:RUNNER_OS } else { "Windows" }
            Architecture = if ($env:RUNNER_ARCH) { $env:RUNNER_ARCH } else { [System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString() }
            Version = [string]$version
        }
    },
    [scriptblock]$TempPathProbe = {
        param([string]$Path)
        if (-not $Path) {
            return $false
        }
        New-Item -ItemType Directory -Force -Path $Path | Out-Null
        $probePath = Join-Path $Path ("muxtv-preflight-" + [Guid]::NewGuid().ToString("N") + ".tmp")
        try {
            Set-Content -LiteralPath $probePath -Value "writable" -Encoding ascii
            Test-Path -LiteralPath $probePath -PathType Leaf
        } finally {
            Remove-Item -LiteralPath $probePath -Force -ErrorAction SilentlyContinue
        }
    },
    [scriptblock]$ResourceProbe = {
        param([string]$ResolvedRepositoryRoot, [bool]$MeasurePhysicalMemory)
        $driveRoot = [System.IO.Path]::GetPathRoot($ResolvedRepositoryRoot)
        $drive = [System.IO.DriveInfo]::new($driveRoot)
        $memory = if ($MeasurePhysicalMemory) {
            $computerSystem = Get-CimInstance -ClassName Win32_ComputerSystem
            [Math]::Round([double]$computerSystem.TotalPhysicalMemory / 1GB, 2)
        } else {
            $null
        }
        [pscustomobject]@{
            FreeDiskGb = [Math]::Round($drive.AvailableFreeSpace / 1GB, 2)
            PhysicalMemoryGb = $memory
        }
    },
    [scriptblock]$AndroidToolchainProbe = {
        param([string]$ResolvedRepositoryRoot)
        $javaResult = Invoke-PreflightProcess -FilePath "java" -ArgumentList @("-version")
        $javaResult.Output | ForEach-Object { Write-Host $_ }
        if ($javaResult.ExitCode -ne 0) {
            throw "Self-hosted runner preflight failed: Java is unavailable."
        }

        $androidInitializer = Join-Path $ResolvedRepositoryRoot "tools\android\Initialize-AndroidSdkEnvironment.ps1"
        if ($env:GITHUB_PATH) {
            . $androidInitializer -PersistForGitHubActions
        } else {
            . $androidInitializer
        }
        . (Join-Path $ResolvedRepositoryRoot "tools\android\AndroidSdk.ps1")
        Get-AndroidSdkTools
    },
    [scriptblock]$AdbDeviceProbe = {
        param([string]$AdbPath)
        Invoke-PreflightProcess -FilePath $AdbPath -ArgumentList @("devices")
    },
    [scriptblock]$SystemImageProbe = {
        param([object]$Tools, [int[]]$RequiredApis)
        @(
            foreach ($api in $RequiredApis) {
                $apiRoot = Join-Path $Tools.Root "system-images\android-$api"
                if (Test-Path -LiteralPath $apiRoot -PathType Container) {
                    $installed = Get-ChildItem -LiteralPath $apiRoot -Recurse -File -Filter "source.properties" -ErrorAction SilentlyContinue |
                        Select-Object -First 1
                    if ($null -ne $installed) {
                        $api
                    }
                }
            }
        )
    },
    [scriptblock]$DnsResolver = {
        param([string]$HostName)
        [System.Net.Dns]::GetHostAddresses($HostName) | ForEach-Object { $_.IPAddressToString }
    },
    [scriptblock]$HttpsProbe = {
        param([uri]$Uri)
        $nodeCommand = Get-Command node -ErrorAction SilentlyContinue
        $nodePath = if ($null -ne $nodeCommand) { $nodeCommand.Source } else { $null }
        if (-not $nodePath -and $env:RUNNER_TEMP) {
            $runnerRoot = Split-Path (Split-Path $env:RUNNER_TEMP -Parent) -Parent
            $nodePath = @(
                (Join-Path $runnerRoot "externals\node24\bin\node.exe"),
                (Join-Path $runnerRoot "externals\node20\bin\node.exe")
            ) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
        }
        if (-not $nodePath) {
            throw "Node runtime is unavailable."
        }

        $probeScript = 'const https=require("https");const request=https.request(process.argv[1],{method:"HEAD",timeout:15000},response=>{console.log(response.statusCode);response.resume();});request.on("timeout",()=>request.destroy(new Error("timeout")));request.on("error",error=>{console.error(error.code||error.name);process.exit(1);});request.end();'
        $output = @(& $nodePath -e $probeScript $Uri.AbsoluteUri 2>&1)
        if ($LASTEXITCODE -ne 0 -or $output.Count -eq 0) {
            throw "Node HTTPS probe failed."
        }
        [int]$output[-1]
    }
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$startedAt = [DateTimeOffset]::UtcNow
$endpointResults = [System.Collections.Generic.List[object]]::new()
$failure = $null
$freeDiskGb = $null
$physicalMemoryGb = $null
$connectedDeviceCount = $null
$runnerVersion = $null
$runnerTempPath = $null
$runnerTempWritable = $false
$installedSystemImageApis = @()
$normalizedExpectedLabels = @(
    $ExpectedRunnerLabels |
        ForEach-Object { ([string]$_).Trim() } |
        Where-Object { $_ } |
        Sort-Object -Unique
)

$actionsResultsUri = if ($env:ACTIONS_RESULTS_URL) {
    $candidate = [uri]$env:ACTIONS_RESULTS_URL
    if ($candidate.Scheme -cne "https") {
        throw "Self-hosted runner preflight failed: ACTIONS_RESULTS_URL must use HTTPS."
    }
    $candidate
} else {
    [uri]"https://results-receiver.actions.githubusercontent.com/"
}

$artifactEndpoints = @(
    [pscustomobject]@{
        Name = "github-actions-results"
        Uri = $actionsResultsUri
    },
    [pscustomobject]@{
        Name = "azure-blob-representative"
        Uri = [uri]"https://productionresultssa0.blob.core.windows.net/"
    }
)

function Invoke-PreflightProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [ValidateRange(1, 120)][int]$TimeoutSeconds = 15
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in $ArgumentList) {
        $startInfo.ArgumentList.Add($argument)
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw "Unable to start required process."
        }
        $standardOutput = $process.StandardOutput.ReadToEndAsync()
        $standardError = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill($true)
            throw "Required process exceeded the ${TimeoutSeconds}-second preflight timeout."
        }
        [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = @(
                $standardOutput.GetAwaiter().GetResult() -split "`r?`n"
                $standardError.GetAwaiter().GetResult() -split "`r?`n"
            ) | Where-Object { $_ }
        }
    } finally {
        $process.Dispose()
    }
}

try {
    $resolvedRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
    $runnerMetadata = & $RunnerMetadataProbe
    $runnerVersion = ([string]$runnerMetadata.Version).Trim()
    if (-not $runnerVersion) {
        throw "Self-hosted runner preflight failed: runner version is unavailable."
    }
    foreach ($propertyName in @("Name", "Os", "Architecture")) {
        if (-not ([string]$runnerMetadata.$propertyName).Trim()) {
            throw "Self-hosted runner preflight failed: runner metadata is incomplete."
        }
    }

    $runnerTempPath = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [System.IO.Path]::GetTempPath() }
    $runnerTempWritable = [bool](& $TempPathProbe $runnerTempPath)
    if (-not $runnerTempWritable) {
        throw "Self-hosted runner preflight failed: temporary path is not writable."
    }

    $resourceSnapshot = & $ResourceProbe $resolvedRoot ($MinimumPhysicalMemoryGb -gt 0)
    $freeDiskGb = [double]$resourceSnapshot.FreeDiskGb
    if ($freeDiskGb -lt $MinimumFreeDiskGb) {
        throw "Self-hosted runner preflight failed: free disk is below ${MinimumFreeDiskGb} GB."
    }

    if ($MinimumPhysicalMemoryGb -gt 0) {
        $physicalMemoryGb = [double]$resourceSnapshot.PhysicalMemoryGb
        if ($physicalMemoryGb -lt $MinimumPhysicalMemoryGb) {
            throw "Self-hosted runner preflight failed: physical memory is below ${MinimumPhysicalMemoryGb} GB."
        }
    }

    foreach ($endpoint in $artifactEndpoints) {
        $hostName = $endpoint.Uri.DnsSafeHost
        $addresses = $null
        try {
            $addresses = @(& $DnsResolver $hostName)
            if ($addresses.Count -eq 0) {
                throw "resolver returned no addresses"
            }
        } catch {
            throw "Self-hosted runner preflight failed: artifact endpoint DNS unavailable for $hostName."
        }

        $statusCode = $null
        try {
            $statusCode = [int](& $HttpsProbe $endpoint.Uri)
            if ($statusCode -lt 100 -or $statusCode -gt 599 -or $statusCode -ge 500) {
                throw "invalid HTTP status"
            }
        } catch {
            throw "Self-hosted runner preflight failed: artifact endpoint HTTPS unavailable for $hostName."
        }

        $endpointResults.Add([pscustomobject]@{
            name = $endpoint.Name
            host = $hostName
            resolved_address_count = $addresses.Count
            https_status = $statusCode
        })
    }

    if (-not $SkipAndroidToolchain) {
        $tools = & $AndroidToolchainProbe $resolvedRoot
        foreach ($toolPath in @($tools.Adb, $tools.Emulator)) {
            if (-not (Test-Path -LiteralPath $toolPath -PathType Leaf)) {
                throw "Self-hosted runner preflight failed: required Android SDK tool is unavailable."
            }
        }

        if ($ExpectedSystemImageApis.Count -gt 0) {
            $installedSystemImageApis = @(& $SystemImageProbe $tools $ExpectedSystemImageApis | Sort-Object -Unique)
            $missingApis = @($ExpectedSystemImageApis | Where-Object { $_ -notin $installedSystemImageApis })
            if ($missingApis.Count -gt 0) {
                throw "Self-hosted runner preflight failed: expected emulator system image is unavailable for API $([string]::Join(', ', $missingApis))."
            }
        }

        $adbResult = & $AdbDeviceProbe $tools.Adb
        $deviceLines = @($adbResult.Output)
        if ($adbResult.ExitCode -ne 0) {
            throw "Self-hosted runner preflight failed: ADB device inspection failed."
        }
        $connectedDeviceRows = @(
            $deviceLines |
                ForEach-Object { ([string]$_).Trim() } |
                Where-Object {
                    $_ -and
                    $_ -notmatch '^List of devices attached' -and
                    $_ -notmatch '^\* daemon '
                }
        )
        $connectedDeviceCount = $connectedDeviceRows.Count
        if ($RequireNoConnectedDevice) {
            & (Join-Path $PSScriptRoot "Assert-NoConnectedAndroidDevice.ps1") -DeviceLines $deviceLines
        }
    }
} catch {
    $failure = $_.Exception.Message
}

$finishedAt = [DateTimeOffset]::UtcNow
$evidence = [ordered]@{
    schema_version = 1
    status = if ($null -eq $failure) { "passed" } else { "failed" }
    started_at_utc = $startedAt.ToString("O")
    finished_at_utc = $finishedAt.ToString("O")
    duration_ms = [Math]::Round(($finishedAt - $startedAt).TotalMilliseconds)
    runner_name = [string]$env:RUNNER_NAME
    runner_os = [string]$env:RUNNER_OS
    runner_arch = [string]$env:RUNNER_ARCH
    runner_version = $runnerVersion
    runner_temp_path = $runnerTempPath
    runner_temp_writable = $runnerTempWritable
    expected_runner_labels = $normalizedExpectedLabels
    expected_system_image_apis = @($ExpectedSystemImageApis | Sort-Object -Unique)
    installed_system_image_apis = $installedSystemImageApis
    free_disk_gb = $freeDiskGb
    physical_memory_gb = $physicalMemoryGb
    connected_device_count = $connectedDeviceCount
    endpoints = @($endpointResults)
    failure = $failure
}

$evidenceDirectory = Split-Path -Parent $EvidencePath
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$evidence | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $EvidencePath -Encoding utf8

if ($null -ne $failure) {
    throw $failure
}

Write-Host "Self-hosted runner preflight passed."
