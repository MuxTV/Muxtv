[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$androidSdkPath = Join-Path $PSScriptRoot "AndroidSdk.ps1"
$tvValidationPath = Join-Path $PSScriptRoot "Invoke-TvDeviceValidation.ps1"
$benchmarkPath = Join-Path $PSScriptRoot "Invoke-BenchmarkDryRun.ps1"
$catalogDevicePath = Join-Path $PSScriptRoot "Invoke-CatalogDatabaseDeviceValidation.ps1"
$playerDevicePath = Join-Path $PSScriptRoot "Invoke-PlayerProxyDeviceValidation.ps1"
$measurementProfilesPath = Join-Path $repositoryRoot "tools\measurements\MeasurementProfiles.ps1"
$measurementSeriesPath = Join-Path $repositoryRoot "tools\measurements\Invoke-MeasurementSeriesCore.ps1"

$errors = [System.Collections.Generic.List[string]]::new()

function Add-ContractError {
    param([Parameter(Mandatory)][string]$Message)
    $script:errors.Add($Message)
}

$androidSdk = Get-Content -LiteralPath $androidSdkPath -Raw
$tvValidation = Get-Content -LiteralPath $tvValidationPath -Raw
$benchmark = Get-Content -LiteralPath $benchmarkPath -Raw
$catalogDevice = Get-Content -LiteralPath $catalogDevicePath -Raw
$playerDevice = Get-Content -LiteralPath $playerDevicePath -Raw
$measurementProfiles = Get-Content -LiteralPath $measurementProfilesPath -Raw
$measurementSeries = Get-Content -LiteralPath $measurementSeriesPath -Raw

if ($androidSdk -notmatch '(?m)^function\s+Get-MuxTvCanonicalAvdName\s*\{') {
    Add-ContractError "AndroidSdk must own canonical MuxTV AVD identity."
}
if ($androidSdk -match 'AllowOldEdgeFallback') {
    Add-ContractError "Android TV system-image fallback is forbidden; API 26 and API 36 must resolve exactly."
}

. $androidSdkPath

try {
    $oldName = Get-MuxTvCanonicalAvdName -Api 26
    $currentName = Get-MuxTvCanonicalAvdName -Api 36
    if ($oldName -cne 'MuxTV_TV_OLD_API26') {
        Add-ContractError "API 26 canonical AVD name is incorrect."
    }
    if ($currentName -cne 'MuxTV_TV_CURRENT_API36') {
        Add-ContractError "API 36 canonical AVD name is incorrect."
    }
    try {
        $null = Get-MuxTvCanonicalAvdName -Api 30
        Add-ContractError "Canonical AVD identity must reject APIs other than 26 and 36."
    } catch {
        # Expected.
    }
} catch {
    Add-ContractError "Canonical AVD helper is not executable."
}

$forbiddenByFile = [ordered]@{
    'Invoke-BenchmarkDryRun.ps1' = @('MuxTV_BENCHMARK_API36')
    'Invoke-CatalogDatabaseDeviceValidation.ps1' = @('MuxTV_CATALOG_MEASUREMENT_API')
    'Invoke-PlayerProxyDeviceValidation.ps1' = @('MuxTV_PLAYER_MEASUREMENT_API')
    'Invoke-MeasurementSeriesCore.ps1' = @('MuxTV_VARIANCE_', 'Remove-MeasurementAvd')
}
$fileContent = @{
    'Invoke-BenchmarkDryRun.ps1' = $benchmark
    'Invoke-CatalogDatabaseDeviceValidation.ps1' = $catalogDevice
    'Invoke-PlayerProxyDeviceValidation.ps1' = $playerDevice
    'Invoke-MeasurementSeriesCore.ps1' = $measurementSeries
}
foreach ($entry in $forbiddenByFile.GetEnumerator()) {
    foreach ($forbidden in $entry.Value) {
        if ($fileContent[$entry.Key].Contains($forbidden, [System.StringComparison]::Ordinal)) {
            Add-ContractError "$($entry.Key) still owns a non-canonical AVD identity/lifecycle: $forbidden"
        }
    }
}

foreach ($caller in @(
    @{ Name = 'Invoke-TvDeviceValidation.ps1'; Content = $tvValidation },
    @{ Name = 'Invoke-BenchmarkDryRun.ps1'; Content = $benchmark },
    @{ Name = 'Invoke-CatalogDatabaseDeviceValidation.ps1'; Content = $catalogDevice },
    @{ Name = 'Invoke-PlayerProxyDeviceValidation.ps1'; Content = $playerDevice },
    @{ Name = 'Invoke-MeasurementSeriesCore.ps1'; Content = $measurementSeries }
)) {
    if ($caller.Content -notmatch 'Get-MuxTvCanonicalAvdName') {
        Add-ContractError "$($caller.Name) must obtain AVD identity from Get-MuxTvCanonicalAvdName."
    }
}

if ($measurementProfiles -match 'AllowOldEdgeFallback') {
    Add-ContractError "Measurement profiles must not expose Android TV fallback policy."
}

if ($errors.Count -gt 0) {
    throw ("MuxTV two-AVD contract failed.`n" + [string]::Join([Environment]::NewLine, $errors))
}

Write-Host "MuxTV two-AVD contract is valid."
