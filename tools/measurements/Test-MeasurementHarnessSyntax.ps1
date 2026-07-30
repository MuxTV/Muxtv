[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$evidenceDirectory = Join-Path $repositoryRoot ".work\evidence\measurement-harness-syntax"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$diagnosticPath = Join-Path $evidenceDirectory "measurement-harness-syntax.log"
$profileScript = Join-Path $PSScriptRoot "MeasurementProfiles.ps1"
$seriesEntryScript = Join-Path $PSScriptRoot "Invoke-MeasurementSeries.ps1"
$seriesCoreScript = Join-Path $PSScriptRoot "Invoke-MeasurementSeriesCore.ps1"
$finalizerScript = Join-Path $PSScriptRoot "Finalize-MeasurementSeriesEvidence.ps1"
$files = @($profileScript, $seriesEntryScript, $seriesCoreScript, $finalizerScript)
$messages = [System.Collections.Generic.List[string]]::new()

foreach ($file in $files) {
    if (-not (Test-Path $file -PathType Leaf)) {
        $messages.Add("Missing measurement harness script: " + [System.IO.Path]::GetFileName($file))
        continue
    }

    $tokens = $null
    $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile(
        $file,
        [ref]$tokens,
        [ref]$parseErrors
    )
    foreach ($parseError in @($parseErrors)) {
        $location = "{0}:{1}:{2}" -f `
            [System.IO.Path]::GetFileName($file), `
            $parseError.Extent.StartLineNumber, `
            $parseError.Extent.StartColumnNumber
        $messages.Add($location + " " + $parseError.Message)
    }
}

if (Test-Path $profileScript -PathType Leaf) {
    . $profileScript
    $expected = [ordered]@{
        "current-normal" = @{ Api = 36; Ram = 2048; Cpu = 2; Fallback = $false }
        "old-edge-normal" = @{ Api = 26; Ram = 1536; Cpu = 2; Fallback = $true }
        "current-low-ram" = @{ Api = 36; Ram = 1024; Cpu = 2; Fallback = $false }
    }
    $ids = @(Get-MuxTvMeasurementProfileIds)
    if ($ids.Count -ne $expected.Count -or
        [string]::Join("|", $ids) -cne [string]::Join("|", @($expected.Keys))) {
        $messages.Add("Measurement profile IDs are not in the canonical order.")
    }
    foreach ($id in @($expected.Keys)) {
        $profile = Get-MuxTvMeasurementProfile -Id $id
        $contract = $expected[$id]
        if ([string]$profile.Id -cne $id -or
            [int]$profile.RequestedApi -ne [int]$contract.Api -or
            [int]$profile.RamMb -ne [int]$contract.Ram -or
            [int]$profile.CpuCores -ne [int]$contract.Cpu -or
            [bool]$profile.AllowOldEdgeFallback -ne [bool]$contract.Fallback) {
            $messages.Add("Measurement profile contract is invalid: $id")
        }
    }
    try {
        $null = Get-MuxTvMeasurementProfile -Id "unknown-profile"
        $messages.Add("Unknown measurement profile was accepted.")
    } catch {
        # Expected fail-closed behavior.
    }
}

if (Test-Path $seriesEntryScript -PathType Leaf) {
    $entryContent = Get-Content -Path $seriesEntryScript -Raw -Encoding utf8
    foreach ($token in @(
        "Wait-MeasurementStableAndroidBoot",
        "consecutiveReadyChecks",
        "Invoke-MeasurementSeriesCore.ps1",
        "Set-Alias"
    )) {
        if ($entryContent -notmatch [regex]::Escape($token)) {
            $messages.Add("Measurement series entry point is missing required contract token: $token")
        }
    }
}

if (Test-Path $seriesCoreScript -PathType Leaf) {
    $seriesContent = Get-Content -Path $seriesCoreScript -Raw -Encoding utf8
    $requiredTokens = @(
        "finally",
        "Stop-TvEmulator",
        "Remove-MeasurementAvd",
        "Invoke-CatalogDatabaseMeasurement.ps1",
        "Invoke-PlayerProxyMeasurement.ps1",
        ":core:testing:analyzeMeasurementSeries"
    )
    foreach ($token in $requiredTokens) {
        if ($seriesContent -notmatch [regex]::Escape($token)) {
            $messages.Add("Measurement series core is missing required contract token: $token")
        }
    }
    $forbiddenTokens = @(
        "ForEach-Object -Parallel",
        "Start-Job",
        "Start-ThreadJob"
    )
    foreach ($token in $forbiddenTokens) {
        if ($seriesContent -match [regex]::Escape($token)) {
            $messages.Add("Measurement series core contains forbidden parallel execution: $token")
        }
    }
}

if (Test-Path $finalizerScript -PathType Leaf) {
    $finalizerContent = Get-Content -Path $finalizerScript -Raw -Encoding utf8
    foreach ($token in @("measurement-series-interrupted", 'status = "interrupted"')) {
        if ($finalizerContent -notmatch [regex]::Escape($token)) {
            $messages.Add("Measurement finalizer is missing required contract token: $token")
        }
    }
}

if ($messages.Count -gt 0) {
    $message = "Measurement harness validation failed." + [Environment]::NewLine +
        [string]::Join([Environment]::NewLine, $messages)
    Set-Content -Path $diagnosticPath -Value $message -Encoding utf8
    Write-Host $message
    throw "Measurement harness validation failed."
}

$message = "Measurement profile catalog and sequential harness syntax are valid."
Set-Content -Path $diagnosticPath -Value $message -Encoding utf8
Write-Host $message
