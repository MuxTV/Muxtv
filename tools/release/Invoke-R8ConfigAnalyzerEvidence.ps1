[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$SourceCommit,

    [string]$EvidenceRoot = ".work/evidence/release-r8"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
$reportRelativePath = "app\tv\build\reports\r8\r8-config-analyzer-release.html"
$reportPath = Join-Path $repositoryRoot $reportRelativePath
$evidenceDirectory = if ([System.IO.Path]::IsPathRooted($EvidenceRoot)) {
    $EvidenceRoot
} else {
    Join-Path $repositoryRoot $EvidenceRoot
}
$metadataPath = Join-Path $evidenceDirectory "r8-config-analyzer.json"

if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "Gradle wrapper is missing."
}

Set-Location $repositoryRoot
& (Join-Path $repositoryRoot "tools\ci\Assert-EvidenceCommit.ps1") -ExpectedCommit $SourceCommit

if (Test-Path -LiteralPath $reportPath -PathType Leaf) {
    Remove-Item -LiteralPath $reportPath -Force
}

& $gradleWrapper `
    :app:tv:analyzeReleaseR8Config `
    --no-daemon `
    --stacktrace `
    --console=plain `
    --no-problems-report
if ($LASTEXITCODE -ne 0) {
    throw "R8 Configuration Analyzer task failed."
}

if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
    throw "R8 Configuration Analyzer completed without the expected report."
}

$report = Get-Item -LiteralPath $reportPath
if ($report.Length -lt 1) {
    throw "R8 Configuration Analyzer report is empty."
}

$reportSha256 = (Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant()
New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null

$metadata = [ordered]@{
    schemaVersion = 1
    repository = "MuxTV/Muxtv"
    sourceCommit = $SourceCommit
    task = ":app:tv:analyzeReleaseR8Config"
    reportPath = "app/tv/build/reports/r8/r8-config-analyzer-release.html"
    reportSha256 = $reportSha256
    reportByteCount = [long]$report.Length
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    claim = "ANALYZER_REPORT_ONLY"
}
$metadata | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $metadataPath -Encoding utf8NoBOM

Write-Host "R8 Configuration Analyzer evidence generated."
Write-Host "sourceCommit=$SourceCommit"
Write-Host "reportSha256=$reportSha256"
Write-Host "metadata=.work/evidence/release-r8/r8-config-analyzer.json"
