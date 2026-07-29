[CmdletBinding()]
param(
    [switch]$PersistForGitHubActions
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$candidates = [System.Collections.Generic.List[string]]::new()
foreach ($value in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
    if (-not [string]::IsNullOrWhiteSpace($value)) {
        $candidates.Add($value.Trim())
    }
}

$localApplicationData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
if (-not [string]::IsNullOrWhiteSpace($localApplicationData)) {
    $candidates.Add((Join-Path $localApplicationData "Android\Sdk"))
}
if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
    $candidates.Add((Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk"))
}

$resolved = $null
$seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($candidate in $candidates) {
    if (-not $seen.Add($candidate)) {
        continue
    }
    if (Test-Path $candidate -PathType Container) {
        $resolved = (Resolve-Path $candidate).Path
        break
    }
}

if ([string]::IsNullOrWhiteSpace($resolved)) {
    throw "Android SDK could not be resolved from configured variables or the standard Windows SDK location."
}

$env:ANDROID_SDK_ROOT = $resolved
$env:ANDROID_HOME = $resolved

if ($PersistForGitHubActions) {
    if ([string]::IsNullOrWhiteSpace($env:GITHUB_ENV)) {
        throw "GITHUB_ENV is unavailable; Android SDK environment cannot be persisted for later Actions steps."
    }
    @(
        "ANDROID_SDK_ROOT=$resolved"
        "ANDROID_HOME=$resolved"
    ) | Add-Content -Path $env:GITHUB_ENV -Encoding utf8
}

Write-Host "Android SDK environment initialized."
