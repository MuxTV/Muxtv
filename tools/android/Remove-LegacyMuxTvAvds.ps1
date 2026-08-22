[CmdletBinding()]
param(
    [switch]$Apply,

    [string[]]$AvdNames = @(),

    [scriptblock]$AvdNameProbe = $null,

    [scriptblock]$DeleteAvd = $null
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "AndroidSdk.ps1")
. (Join-Path $PSScriptRoot "MuxTvAvdOwnership.ps1")

$names = if ($PSBoundParameters.ContainsKey("AvdNames")) {
    @($AvdNames)
} else {
    if ($null -eq $AvdNameProbe) {
        $AvdNameProbe = {
            $tools = Get-AndroidSdkTools
            $output = @(& $tools.AvdManager list avd -c 2>&1)
            $exitCode = $LASTEXITCODE
            if ($exitCode -ne 0) {
                throw "Unable to enumerate Android Virtual Devices."
            }

            foreach ($line in $output) {
                $name = ([string]$line).Trim()
                if (-not [string]::IsNullOrWhiteSpace($name)) {
                    $name
                }
            }
        }
    }
    @(& $AvdNameProbe)
}

$legacyNames = @(Get-LegacyMuxTvAvdNames -Names $names)
if ($legacyNames.Count -eq 0) {
    Write-Host "No legacy repository-owned MuxTV AVD definitions were found."
    return @()
}

Write-Host "Legacy repository-owned MuxTV AVD definitions:"
foreach ($name in $legacyNames) {
    Write-Host "  $name"
}

if (-not $Apply) {
    Write-Host "Dry run only. Re-run with -Apply to delete exactly these allowlisted legacy MuxTV AVD definitions."
    return $legacyNames
}

if ($null -eq $DeleteAvd) {
    $tools = Get-AndroidSdkTools
    $DeleteAvd = {
        param([string]$Name)

        $output = @(& $tools.AvdManager delete avd --name $Name 2>&1)
        $exitCode = $LASTEXITCODE
        $output | ForEach-Object { Write-Host $_ }
        if ($exitCode -ne 0) {
            throw "Unable to delete legacy MuxTV AVD: $Name"
        }
    }.GetNewClosure()
}

foreach ($name in $legacyNames) {
    & $DeleteAvd $name
    Write-Host "Deleted legacy MuxTV AVD: $name"
}

return $legacyNames
