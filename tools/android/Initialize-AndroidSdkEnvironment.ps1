[CmdletBinding()]
param(
    [switch]$PersistForGitHubActions
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Add-PathEntryIfMissing {
    param(
        [Parameter(Mandatory)][string]$Entry,
        [switch]$Persist
    )

    if (-not (Test-Path $Entry -PathType Container)) {
        throw "Required Windows runtime directory does not exist."
    }

    $normalizedEntry = [System.IO.Path]::GetFullPath($Entry).TrimEnd('\')
    $currentEntries = @(
        ([string]$env:PATH).Split(
            [System.IO.Path]::PathSeparator,
            [System.StringSplitOptions]::RemoveEmptyEntries
        ) | ForEach-Object {
            try {
                [System.IO.Path]::GetFullPath($_).TrimEnd('\')
            } catch {
                $_.Trim().TrimEnd('\')
            }
        }
    )
    $alreadyPresent = $currentEntries | Where-Object {
        [string]::Equals($_, $normalizedEntry, [System.StringComparison]::OrdinalIgnoreCase)
    }
    if ($null -eq $alreadyPresent) {
        $env:PATH = "$normalizedEntry$([System.IO.Path]::PathSeparator)$env:PATH"
    }

    if ($Persist) {
        if ([string]::IsNullOrWhiteSpace($env:GITHUB_PATH)) {
            throw "GITHUB_PATH is unavailable; the Windows runtime path cannot be persisted."
        }
        # GitHub Actions creates a fresh process environment for every step. Persist the
        # entry even when it already exists in this step's PATH; otherwise the next step
        # can lose System32 and batch wrappers such as gradlew.bat cannot find findstr.exe.
        $normalizedEntry | Add-Content -Path $env:GITHUB_PATH -Encoding utf8
    }
}

$windowsRoot = if (-not [string]::IsNullOrWhiteSpace($env:SystemRoot)) {
    $env:SystemRoot
} else {
    [Environment]::GetFolderPath([Environment+SpecialFolder]::Windows)
}
if ([string]::IsNullOrWhiteSpace($windowsRoot)) {
    throw "Windows system root could not be resolved."
}
Add-PathEntryIfMissing `
    -Entry (Join-Path $windowsRoot "System32") `
    -Persist:$PersistForGitHubActions

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
# The runner must not auto-attach paired phones or TVs discovered through mDNS.
# Explicit emulator/device connections remain available to the owning harness.
$env:ADB_MDNS_AUTO_CONNECT = "0"

if ($PersistForGitHubActions) {
    if ([string]::IsNullOrWhiteSpace($env:GITHUB_ENV)) {
        throw "GITHUB_ENV is unavailable; Android SDK environment cannot be persisted for later Actions steps."
    }
    @(
        "ANDROID_SDK_ROOT=$resolved"
        "ANDROID_HOME=$resolved"
        "ADB_MDNS_AUTO_CONNECT=0"
    ) | Add-Content -Path $env:GITHUB_ENV -Encoding utf8
}

Write-Host "Android SDK and Windows runtime environment initialized."
