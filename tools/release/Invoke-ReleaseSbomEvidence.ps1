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

$scriptDirectory = Split-Path -Parent $PSCommandPath
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $scriptDirectory "..\..")).Path
$sbomPath = Join-Path $repositoryRoot "app\tv\build\reports\sbom\muxtv-tv-release.cdx.json"
$evidenceDirectory = Join-Path $repositoryRoot ".work\evidence\release-sbom"
$metadataPath = Join-Path $evidenceDirectory "release-sbom-evidence.json"

Push-Location $repositoryRoot
try {
    & .\tools\ci\Assert-EvidenceCommit.ps1 -ExpectedCommit $SourceCommit

    Remove-Item -LiteralPath $sbomPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $evidenceDirectory -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null

    & .\gradlew.bat :app:tv:cyclonedxDirectBom `
        --no-daemon `
        --stacktrace `
        --console=plain `
        --no-problems-report
    if ($LASTEXITCODE -ne 0) {
        throw "CycloneDX releaseRuntimeClasspath SBOM generation failed."
    }

    if (-not (Test-Path -LiteralPath $sbomPath -PathType Leaf)) {
        throw "Expected release SBOM was not generated: app/tv/build/reports/sbom/muxtv-tv-release.cdx.json"
    }

    $sbomFile = Get-Item -LiteralPath $sbomPath
    if ($sbomFile.Length -lt 1) {
        throw "Generated release SBOM is empty."
    }

    $sbomRaw = Get-Content -LiteralPath $sbomPath -Raw
    $sbom = $sbomRaw | ConvertFrom-Json -Depth 100

    if ($sbom.bomFormat -cne "CycloneDX") {
        throw "Release SBOM bomFormat must be CycloneDX; actual=$($sbom.bomFormat)."
    }
    if ([string]$sbom.specVersion -cne "1.6") {
        throw "Release SBOM specVersion must be 1.6; actual=$($sbom.specVersion)."
    }
    if ($null -eq $sbom.metadata -or $null -eq $sbom.metadata.component) {
        throw "Release SBOM is missing its metadata component."
    }
    if ([string]$sbom.metadata.component.type -cne "application") {
        throw "Release SBOM metadata component must be an application."
    }
    if ([string]$sbom.metadata.component.name -cne "app.muxtv.tv") {
        throw "Release SBOM metadata component does not identify app.muxtv.tv."
    }
    if ([string]::IsNullOrWhiteSpace([string]$sbom.metadata.component.version)) {
        throw "Release SBOM metadata component is missing versionName."
    }

    $components = @($sbom.components)
    if ($components.Count -lt 1) {
        throw "Release SBOM contains no resolved releaseRuntimeClasspath components."
    }

    $dependencies = @($sbom.dependencies)
    if ($dependencies.Count -lt 1) {
        throw "Release SBOM contains no dependency graph nodes."
    }

    $forbiddenDependencyFragments = @(
        "pkg:maven/junit/junit@",
        "pkg:maven/androidx.test/",
        "pkg:maven/androidx.benchmark/benchmark-macro",
        "pkg:maven/com.squareup.okhttp3/mockwebserver3@"
    )
    foreach ($forbidden in $forbiddenDependencyFragments) {
        if ($sbomRaw.Contains($forbidden, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Release SBOM leaked a test/benchmark dependency outside releaseRuntimeClasspath: $forbidden"
        }
    }

    $sbomSha256 = (Get-FileHash -LiteralPath $sbomPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $sbomRelativePath = [System.IO.Path]::GetRelativePath($repositoryRoot, $sbomPath).Replace('\', '/')

    $metadata = [ordered]@{
        schemaVersion = 1
        repository = "MuxTV/Muxtv"
        sourceCommit = $SourceCommit
        plugin = "org.cyclonedx.bom"
        pluginVersion = "3.4.1"
        dependencyConfiguration = "releaseRuntimeClasspath"
        sbomPath = $sbomRelativePath
        sbomSizeBytes = $sbomFile.Length
        sbomSha256 = $sbomSha256
        bomFormat = [string]$sbom.bomFormat
        specVersion = [string]$sbom.specVersion
        componentName = [string]$sbom.metadata.component.name
        componentVersion = [string]$sbom.metadata.component.version
        componentCount = $components.Count
        dependencyNodeCount = $dependencies.Count
    }
    $metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataPath -Encoding utf8

    Write-Host "Release SBOM evidence generated."
    Write-Host "sourceCommit=$SourceCommit"
    Write-Host "dependencyConfiguration=releaseRuntimeClasspath"
    Write-Host "sbomSha256=$sbomSha256"
    Write-Host "sbomSizeBytes=$($sbomFile.Length)"
    Write-Host "components=$($components.Count)"
    Write-Host "dependencies=$($dependencies.Count)"
    Write-Host "metadata=.work/evidence/release-sbom/release-sbom-evidence.json"
    exit 0
}
finally {
    Pop-Location
}
