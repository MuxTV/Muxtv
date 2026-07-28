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

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$measurementScript = Join-Path $PSScriptRoot "Invoke-CatalogDatabaseMeasurement.ps1"
if (-not (Test-Path $measurementScript -PathType Leaf)) {
    throw "Catalog database measurement script was not found."
}
$resolvedEvidenceRoot = if ([System.IO.Path]::IsPathRooted($EvidenceRoot)) {
    $EvidenceRoot
} else {
    Join-Path $repositoryRoot $EvidenceRoot
}
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$shortCommit = $SourceCommit.Substring(0, 12)
$evidenceDirectory = Join-Path $resolvedEvidenceRoot "$timestamp-$shortCommit-catalog-measurement"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$manifestPath = Join-Path $evidenceDirectory "catalog-measurement-device-manifest.json"

$manifest = [ordered]@{
    schemaVersion = 1
    repository = "MuxTV/Muxtv"
    branch = $SourceBranch.Trim()
    commit = $SourceCommit
    mode = "CatalogMeasurement"
    startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    completedAtUtc = $null
    status = "running"
    image = $null
    serial = $null
    ramMb = $RamMb
    cpuCores = $CpuCores
    failure = $null
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
    $avdName = "MuxTV_CATALOG_MEASUREMENT_API$($image.Api)"
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
    Wait-AndroidBoot -Tools $tools -Serial $serial -TimeoutSeconds 360
    $env:ANDROID_SERIAL = $serial
    Collect-AndroidEvidence -Tools $tools -Serial $serial -OutputDirectory $evidenceDirectory

    $measurementArguments = @(
        "-NoProfile",
        "-File", $measurementScript,
        "-SourceCommit", $SourceCommit,
        "-RunnerLabel", "self-hosted-android-tv-api$($image.Api)-$($image.Abi)",
        "-Warmups", "1",
        "-Iterations", "5",
        "-EntryCount", "10000",
        "-OutputName", "catalog-database-measurement.json",
        "-EvidenceDirectory", $evidenceDirectory
    )
    if ($NoDaemon) {
        $measurementArguments += "-NoDaemon"
    }

    & pwsh @measurementArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Catalog database device measurement failed with exit code $LASTEXITCODE."
    }

    $manifest.status = "passed"
} catch {
    $manifest.status = "failed"
    $manifest.failure = $_.Exception.Message
    throw
} finally {
    $manifest.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    if ($null -ne $tools -and $null -ne $serial) {
        try {
            Collect-AndroidEvidence -Tools $tools -Serial $serial -OutputDirectory $evidenceDirectory
        } catch {
            Write-Warning "Unable to collect final catalog measurement device evidence."
        }
        try {
            Stop-TvEmulator -Tools $tools -Serial $serial -Process $process
        } catch {
            Write-Warning "Unable to stop catalog measurement emulator cleanly."
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
    Write-Host "Catalog measurement device evidence: $evidenceDirectory"
}
