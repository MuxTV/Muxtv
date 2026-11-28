[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "CycloneDxMetadataWarnings.ps1")

function Assert-Coordinates([object[]]$Lines, [string[]]$Expected) {
    $actual = @(Get-CycloneDxMetadataWarningCoordinates -Lines $Lines)
    if (($actual.Count -ne $Expected.Count) -or (($actual -join '|') -cne ($Expected -join '|'))) {
        throw "Warning coordinate mismatch: expected=$($Expected -join ',') actual=$($actual -join ',')"
    }
}

function Assert-Rejected([string]$Name, [scriptblock]$Action, [string]$Message) {
    $caught = $false
    try { & $Action } catch {
        if ($_.Exception.Message -notlike $Message) { throw }
        $caught = $true
    }
    if (-not $caught) { throw "Accepted invalid warning fixture: $Name" }
    Write-Host "Rejected warning fixture: $Name"
}

$gav = 'androidx.media3:media3-ui-compose:1.11.1'
$examples = @(
    "Unable to resolve POM for $gav",
    "[CycloneDX] Unable to read POM C:\some path\pom.xml of component $gav",
    "[CycloneDX] POM /tmp/some path/pom.xml of component $gav is not readable",
    "[CycloneDX] Unable to build the effective model of component $gav, reporting what its own POM declares",
    "[CycloneDX] No Maven component resolved for $gav, its metadata is unavailable",
    "[CycloneDX] Component $gav carries no POM artifact, its metadata is unavailable",
    "[CycloneDX] Unable to resolve the POM of component $gav, its metadata is unavailable, because repository failed"
)
foreach ($line in $examples) {
    Assert-Coordinates @($line) @($gav)
}
Assert-Coordinates $examples @($gav)
Assert-Coordinates @('BUILD SUCCESSFUL', 'java.lang.IllegalStateException: An error occurred attempting to resolve effective POM') @()
Assert-Coordinates @() @()
$esc = [char]27
Assert-Coordinates @("$esc[33m$($examples[3])$esc[0m", "Unable to resolve POM for a:b:1") @('a:b:1', $gav)
Assert-Rejected 'malformed-effective-model' { Get-CycloneDxMetadataWarningCoordinates -Lines @('[CycloneDX] Unable to build the effective model of component malformed, reporting what its own POM declares') } '*unrecognized coordinate or format*'
Assert-Rejected 'malformed-legacy' { Get-CycloneDxMetadataWarningCoordinates -Lines @('Unable to resolve POM for broken') } '*unrecognized coordinate or format*'
Assert-Rejected 'unexpected-coordinate-shape' { Get-CycloneDxMetadataWarningCoordinates -Lines @('[CycloneDX] No Maven component resolved for a:b:1:extra, its metadata is unavailable') } '*unrecognized coordinate or format*'
$components = @([pscustomobject]@{ group = 'androidx.media3'; name = 'media3-ui-compose'; version = '1.11.1' })
Assert-CycloneDxWarnedComponentsPresent -Coordinates @($gav) -Components $components
Assert-CycloneDxWarnedComponentsPresent -Coordinates @() -Components @()
Assert-Rejected 'absent-component' { Assert-CycloneDxWarnedComponentsPresent -Coordinates @($gav) -Components @() } '*absent from the SBOM graph*'
Assert-Rejected 'wrong-version' { Assert-CycloneDxWarnedComponentsPresent -Coordinates @('androidx.media3:media3-ui-compose:1.11.2') -Components $components } '*absent from the SBOM graph*'
Assert-Rejected 'wrong-case' { Assert-CycloneDxWarnedComponentsPresent -Coordinates @('Androidx.media3:media3-ui-compose:1.11.1') -Components $components } '*absent from the SBOM graph*'
Write-Host "CycloneDX metadata warning contract PASS (7 formats, deduplication, ANSI, empty input and 6 negative cases)."
