# Reviewed CycloneDX 3.4.x and 3.6.1 Maven metadata warning families.
function Get-CycloneDxMetadataWarningCoordinates {
    param([AllowEmptyCollection()][object[]]$Lines)
    $coordinate = '(?<coordinate>[^\s,:]+:[^\s,:]+:[^\s,:]+)'
    $patterns = @(
        "Unable to resolve POM for\s+$coordinate(?=\s|$)",
        "\[CycloneDX\]\s+Unable to read POM .+ of component $coordinate\s*$",
        "\[CycloneDX\]\s+POM .+ of component $coordinate is not readable\s*$",
        "\[CycloneDX\]\s+Unable to build the effective model of component $coordinate, reporting what its own POM declares\s*$",
        "\[CycloneDX\]\s+No Maven component resolved for $coordinate, its metadata is unavailable\s*$",
        "\[CycloneDX\]\s+Component $coordinate carries no POM artifact, its metadata is unavailable\s*$",
        "\[CycloneDX\]\s+Unable to resolve the POM of component $coordinate, its metadata is unavailable, because .+"
    )
    $knownWarning = 'Unable to resolve POM for\b|\[CycloneDX\]\s+(?:Unable to read POM\b|POM\b.+is not readable|Unable to build the effective model\b|No Maven component resolved\b|Component\b.+carries no POM artifact|Unable to resolve the POM\b)'
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($item in $Lines) {
        $line = [regex]::Replace([string]$item, '\x1B\[[0-?]*[ -/]*[@-~]', '').Trim()
        if (-not [regex]::IsMatch($line, $knownWarning)) { continue }
        $match = $null
        foreach ($pattern in $patterns) {
            $candidate = [regex]::Match($line, $pattern)
            if ($candidate.Success) {
                $match = $candidate
                break
            }
        }
        if ($null -eq $match) {
            throw "CycloneDX metadata warning has an unrecognized coordinate or format."
        }
        [void]$seen.Add($match.Groups['coordinate'].Value)
    }
    $seen | Sort-Object -CaseSensitive
}

function Assert-CycloneDxWarnedComponentsPresent {
    param(
        [AllowEmptyCollection()][string[]]$Coordinates,
        [AllowEmptyCollection()][object[]]$Components
    )
    foreach ($coordinate in $Coordinates) {
        $parts = @($coordinate -split ':')
        if ($parts.Count -ne 3 -or @($parts | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -ne 0) {
            throw "CycloneDX reported an unparseable POM metadata warning coordinate: $coordinate"
        }
        $matchingComponents = @(
            $Components | Where-Object {
                [string]$_.group -ceq $parts[0] -and
                [string]$_.name -ceq $parts[1] -and
                [string]$_.version -ceq $parts[2]
            }
        )
        if ($matchingComponents.Count -lt 1) {
            throw "CycloneDX metadata enrichment failed and the warned component is absent from the SBOM graph: $coordinate"
        }
    }
}
