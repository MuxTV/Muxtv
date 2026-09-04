[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$SourceCommit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Get-JsonPropertyValue {
    param(
        [Parameter(Mandatory)][object]$Object,
        [Parameter(Mandatory)][string]$Name
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Get-ComponentPropertyValue {
    param(
        [Parameter(Mandatory)][object]$Component,
        [Parameter(Mandatory)][string]$Name
    )

    $properties = Get-JsonPropertyValue -Object $Component -Name "properties"
    foreach ($property in @($properties)) {
        if ([string](Get-JsonPropertyValue -Object $property -Name "name") -ceq $Name) {
            return [string](Get-JsonPropertyValue -Object $property -Name "value")
        }
    }
    return $null
}

function Get-ComponentCoordinate {
    param([Parameter(Mandatory)][object]$Component)

    $group = [string](Get-JsonPropertyValue -Object $Component -Name "group")
    $name = [string](Get-JsonPropertyValue -Object $Component -Name "name")
    $version = [string](Get-JsonPropertyValue -Object $Component -Name "version")
    if (-not [string]::IsNullOrWhiteSpace($group) -and
        -not [string]::IsNullOrWhiteSpace($name) -and
        -not [string]::IsNullOrWhiteSpace($version)) {
        return "$group`:$name`:$version"
    }

    $purl = [string](Get-JsonPropertyValue -Object $Component -Name "purl")
    if (-not [string]::IsNullOrWhiteSpace($purl)) {
        return $purl
    }

    return "$name@$version"
}

function Get-DeclaredLicenses {
    param([Parameter(Mandatory)][object]$Component)

    $normalized = [System.Collections.Generic.List[object]]::new()
    $licenseChoices = Get-JsonPropertyValue -Object $Component -Name "licenses"
    foreach ($choice in @($licenseChoices)) {
        if ($null -eq $choice) {
            continue
        }

        $expression = [string](Get-JsonPropertyValue -Object $choice -Name "expression")
        if (-not [string]::IsNullOrWhiteSpace($expression)) {
            [void]$normalized.Add([pscustomobject][ordered]@{
                expression = $expression
            })
            continue
        }

        $license = Get-JsonPropertyValue -Object $choice -Name "license"
        if ($null -eq $license) {
            continue
        }

        $id = [string](Get-JsonPropertyValue -Object $license -Name "id")
        $name = [string](Get-JsonPropertyValue -Object $license -Name "name")
        $url = [string](Get-JsonPropertyValue -Object $license -Name "url")
        if ([string]::IsNullOrWhiteSpace($id) -and
            [string]::IsNullOrWhiteSpace($name) -and
            [string]::IsNullOrWhiteSpace($url)) {
            continue
        }

        [void]$normalized.Add([pscustomobject][ordered]@{
            id = if ([string]::IsNullOrWhiteSpace($id)) { $null } else { $id }
            name = if ([string]::IsNullOrWhiteSpace($name)) { $null } else { $name }
            url = if ([string]::IsNullOrWhiteSpace($url)) { $null } else { $url }
        })
    }

    return @($normalized)
}

function Format-LicenseSummary {
    param([Parameter(Mandatory)][object[]]$Licenses)

    $values = @(
        foreach ($license in $Licenses) {
            $expression = [string](Get-JsonPropertyValue -Object $license -Name "expression")
            if (-not [string]::IsNullOrWhiteSpace($expression)) {
                $expression
                continue
            }
            $id = [string](Get-JsonPropertyValue -Object $license -Name "id")
            if (-not [string]::IsNullOrWhiteSpace($id)) {
                $id
                continue
            }
            $name = [string](Get-JsonPropertyValue -Object $license -Name "name")
            if (-not [string]::IsNullOrWhiteSpace($name)) {
                $name
                continue
            }
            $url = [string](Get-JsonPropertyValue -Object $license -Name "url")
            if (-not [string]::IsNullOrWhiteSpace($url)) {
                $url
            }
        }
    )
    return ($values -join ", ").Replace("|", "\|")
}

$scriptDirectory = Split-Path -Parent $PSCommandPath
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $scriptDirectory "..\..")).Path
$sbomPath = Join-Path $repositoryRoot "app\tv\build\reports\sbom\muxtv-tv-release.cdx.json"
$overridePath = Join-Path $repositoryRoot "config\release\license-overrides.json"
$evidenceDirectory = Join-Path $repositoryRoot ".work\evidence\release-license"
$jsonReportPath = Join-Path $evidenceDirectory "release-license-report.json"
$markdownReportPath = Join-Path $evidenceDirectory "release-license-report.md"
$firstPartyNamespace = "MuxTV."

Push-Location $repositoryRoot
try {
    & .\tools\ci\Assert-EvidenceCommit.ps1 -ExpectedCommit $SourceCommit

    Remove-Item -LiteralPath $evidenceDirectory -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null

    $sbomOutput = @(
        & pwsh -NoProfile -File .\tools\release\Invoke-ReleaseSbomEvidence.ps1 `
            -SourceCommit $SourceCommit 2>&1
    )
    $sbomExitCode = $LASTEXITCODE
    $sbomOutput | ForEach-Object { Write-Host ([string]$_) }
    if ($sbomExitCode -ne 0) {
        throw "Exact-head release SBOM evidence generation failed before license inspection."
    }

    if (-not (Test-Path -LiteralPath $sbomPath -PathType Leaf)) {
        throw "Expected exact-head SBOM is missing: app/tv/build/reports/sbom/muxtv-tv-release.cdx.json"
    }
    if (-not (Test-Path -LiteralPath $overridePath -PathType Leaf)) {
        throw "Missing curated release license override file."
    }

    $sbomRaw = Get-Content -LiteralPath $sbomPath -Raw
    $sbom = $sbomRaw | ConvertFrom-Json -Depth 100
    $overrideConfig = (Get-Content -LiteralPath $overridePath -Raw) | ConvertFrom-Json -Depth 20

    $overrideMap = @{}
    foreach ($override in @($overrideConfig.overrides)) {
        $coordinate = [string]$override.coordinate
        if ([string]::IsNullOrWhiteSpace($coordinate)) {
            throw "Curated license override contains an empty coordinate."
        }
        if ($overrideMap.ContainsKey($coordinate)) {
            throw "Duplicate curated license override coordinate: $coordinate"
        }
        $license = [string]$override.license
        $source = [string]$override.source
        $rationale = [string]$override.rationale
        if ([string]::IsNullOrWhiteSpace($license) -or
            [string]::IsNullOrWhiteSpace($source) -or
            [string]::IsNullOrWhiteSpace($rationale)) {
            throw "Curated license override must include coordinate, license, source and rationale: $coordinate"
        }
        $overrideMap[$coordinate] = $override
    }

    $components = @($sbom.components)
    $rows = [System.Collections.Generic.List[object]]::new()
    $firstPartyComponentCount = 0
    $thirdPartyComponentCount = 0
    $unknownThirdPartyLicenseCount = 0
    $usedOverrideCoordinates = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)

    foreach ($component in $components) {
        $coordinate = Get-ComponentCoordinate -Component $component
        $projectPath = Get-ComponentPropertyValue -Component $component -Name "project_path"
        $isFirstParty = -not [string]::IsNullOrWhiteSpace($projectPath) -and $projectPath.StartsWith(":", [System.StringComparison]::Ordinal)
        if ($isFirstParty) {
            $firstPartyComponentCount++
            continue
        }

        $thirdPartyComponentCount++
        $licenses = @(Get-DeclaredLicenses -Component $component)
        $licenseSource = "cyclonedx"
        $overrideSource = $null
        $overrideRationale = $null

        if ($licenses.Count -eq 0 -and $overrideMap.ContainsKey($coordinate)) {
            $override = $overrideMap[$coordinate]
            $licenses = @([pscustomobject][ordered]@{
                id = [string]$override.license
                name = $null
                url = $null
            })
            $licenseSource = "curated-override"
            $overrideSource = [string]$override.source
            $overrideRationale = [string]$override.rationale
            [void]$usedOverrideCoordinates.Add($coordinate)
        }

        if ($licenses.Count -eq 0) {
            $licenseSource = "unknown"
            $unknownThirdPartyLicenseCount++
        }

        [void]$rows.Add([pscustomobject][ordered]@{
            coordinate = $coordinate
            group = [string](Get-JsonPropertyValue -Object $component -Name "group")
            name = [string](Get-JsonPropertyValue -Object $component -Name "name")
            version = [string](Get-JsonPropertyValue -Object $component -Name "version")
            purl = [string](Get-JsonPropertyValue -Object $component -Name "purl")
            licenseSource = $licenseSource
            licenses = $licenses
            overrideSource = $overrideSource
            overrideRationale = $overrideRationale
        })
    }

    foreach ($coordinate in $overrideMap.Keys) {
        if (-not $usedOverrideCoordinates.Contains([string]$coordinate)) {
            throw "Curated license override was not required by the exact-head third-party graph: $coordinate"
        }
    }

    $rows = @($rows | Sort-Object coordinate)
    $sbomSha256 = (Get-FileHash -LiteralPath $sbomPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $overrideSha256 = (Get-FileHash -LiteralPath $overridePath -Algorithm SHA256).Hash.ToLowerInvariant()

    $markdown = [System.Collections.Generic.List[string]]::new()
    [void]$markdown.Add("# MuxTV exact-head third-party license report")
    [void]$markdown.Add("")
    [void]$markdown.Add("- Source commit: ``$SourceCommit``")
    [void]$markdown.Add("- SBOM SHA-256: ``$sbomSha256``")
    [void]$markdown.Add("- First-party project components: $firstPartyComponentCount")
    [void]$markdown.Add("- Third-party components: $thirdPartyComponentCount")
    [void]$markdown.Add("- Unknown third-party licenses: $unknownThirdPartyLicenseCount")
    [void]$markdown.Add("")
    [void]$markdown.Add("First-party ownership is derived from the exact-head CycloneDX ``project_path`` property for repository Gradle projects under the MuxTV namespace policy ``$firstPartyNamespace``.")
    [void]$markdown.Add("")
    [void]$markdown.Add("| Component | License source | License |")
    [void]$markdown.Add("| --- | --- | --- |")
    foreach ($row in $rows) {
        $summary = if (@($row.licenses).Count -eq 0) { "UNKNOWN" } else { Format-LicenseSummary -Licenses @($row.licenses) }
        [void]$markdown.Add("| $($row.coordinate.Replace('|', '\|')) | $($row.licenseSource) | $summary |")
    }
    $markdown | Set-Content -LiteralPath $markdownReportPath -Encoding utf8
    $markdownSha256 = (Get-FileHash -LiteralPath $markdownReportPath -Algorithm SHA256).Hash.ToLowerInvariant()

    $report = [ordered]@{
        schemaVersion = 1
        repository = "MuxTV/Muxtv"
        sourceCommit = $SourceCommit
        sourceSbom = [ordered]@{
            path = "app/tv/build/reports/sbom/muxtv-tv-release.cdx.json"
            sha256 = $sbomSha256
        }
        curatedOverrides = [ordered]@{
            path = "config/release/license-overrides.json"
            sha256 = $overrideSha256
            configuredCount = $overrideMap.Count
            usedCount = $usedOverrideCoordinates.Count
        }
        firstPartyPolicy = [ordered]@{
            ownershipProperty = "project_path"
            namespace = $firstPartyNamespace
        }
        firstPartyComponentCount = $firstPartyComponentCount
        thirdPartyComponentCount = $thirdPartyComponentCount
        unknownThirdPartyLicenseCount = $unknownThirdPartyLicenseCount
        markdownReportSha256 = $markdownSha256
        components = $rows
    }
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonReportPath -Encoding utf8
    $jsonReportSha256 = (Get-FileHash -LiteralPath $jsonReportPath -Algorithm SHA256).Hash.ToLowerInvariant()

    Write-Host "Release license report evidence generated."
    Write-Host "sourceCommit=$SourceCommit"
    Write-Host "sbomSha256=$sbomSha256"
    Write-Host "firstPartyComponentCount=$firstPartyComponentCount"
    Write-Host "thirdPartyComponentCount=$thirdPartyComponentCount"
    Write-Host "unknownThirdPartyLicenseCount=$unknownThirdPartyLicenseCount"
    Write-Host "jsonReportSha256=$jsonReportSha256"
    Write-Host "markdownReportSha256=$markdownSha256"
    Write-Host "jsonReport=.work/evidence/release-license/release-license-report.json"
    Write-Host "markdownReport=.work/evidence/release-license/release-license-report.md"

    if ($unknownThirdPartyLicenseCount -gt 0) {
        throw "Unknown third-party licenses remain: $unknownThirdPartyLicenseCount"
    }

    exit 0
}
finally {
    Pop-Location
}
