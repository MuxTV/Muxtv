[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$androidSdkPath = Join-Path $PSScriptRoot "AndroidSdk.ps1"
$cleanupPath = Join-Path $PSScriptRoot "Remove-LegacyMuxTvAvds.ps1"

if (-not (Test-Path -LiteralPath $cleanupPath -PathType Leaf)) {
    throw "Legacy MuxTV AVD cleanup entry point is missing."
}

. $androidSdkPath

$fixtureNames = @(
    "MuxTV_TV_OLD_API26",
    "MuxTV_TV_CURRENT_API36",
    "MuxTV_TV_OLD_API28",
    "MuxTV_TV_OLD_API30",
    "MuxTV_TV_CURRENT_API35",
    "MuxTV_TV_CURRENT_API37",
    "MuxTV_VARIANCE_current_normal_01_API36",
    "MuxTV_VARIANCE_old_edge_normal_05_API26",
    "MuxTV_BENCHMARK_API36",
    "MuxTV_CATALOG_MEASUREMENT_API36",
    "MuxTV_PLAYER_MEASUREMENT_API36",
    "MuxTV_custom_user_fixture",
    "Pixel_8_API36",
    "tv_1080p_personal"
)

$expectedLegacy = @(
    "MuxTV_BENCHMARK_API36",
    "MuxTV_CATALOG_MEASUREMENT_API36",
    "MuxTV_PLAYER_MEASUREMENT_API36",
    "MuxTV_TV_CURRENT_API35",
    "MuxTV_TV_CURRENT_API37",
    "MuxTV_TV_OLD_API28",
    "MuxTV_TV_OLD_API30",
    "MuxTV_VARIANCE_current_normal_01_API36",
    "MuxTV_VARIANCE_old_edge_normal_05_API26"
)

$actualLegacy = @(Get-LegacyMuxTvAvdNames -Names $fixtureNames)
if ([string]::Join("|", $actualLegacy) -cne [string]::Join("|", $expectedLegacy)) {
    throw "Legacy MuxTV AVD classifier did not return the exact allowlisted historical identities."
}

foreach ($canonical in @("MuxTV_TV_OLD_API26", "MuxTV_TV_CURRENT_API36")) {
    if ($actualLegacy -ccontains $canonical) {
        throw "Canonical MuxTV AVD was classified as legacy: $canonical"
    }
}
foreach ($unrelated in @("MuxTV_custom_user_fixture", "Pixel_8_API36", "tv_1080p_personal")) {
    if ($actualLegacy -ccontains $unrelated) {
        throw "Unrelated AVD was classified as MuxTV legacy: $unrelated"
    }
}

$dryRunDeletes = [System.Collections.Generic.List[string]]::new()
$dryRunDelete = {
    param([string]$Name)
    $dryRunDeletes.Add($Name)
}.GetNewClosure()
$null = & $cleanupPath -AvdNames $fixtureNames -DeleteAvd $dryRunDelete
if ($dryRunDeletes.Count -ne 0) {
    throw "Legacy MuxTV AVD cleanup deleted an AVD without -Apply."
}

$appliedDeletes = [System.Collections.Generic.List[string]]::new()
$applyDelete = {
    param([string]$Name)
    $appliedDeletes.Add($Name)
}.GetNewClosure()
$null = & $cleanupPath -Apply -AvdNames $fixtureNames -DeleteAvd $applyDelete
if ([string]::Join("|", @($appliedDeletes)) -cne [string]::Join("|", $expectedLegacy)) {
    throw "Legacy MuxTV AVD cleanup did not delete exactly the classified legacy identities."
}

Write-Host "Legacy MuxTV AVD cleanup contract passed."
