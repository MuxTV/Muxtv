[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$relativePath = "app\tv\src\main\kotlin\app\muxtv\di\RecentPlaybackModule.kt"
$path = Join-Path $RepositoryRoot $relativePath
if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
    throw "Recent playback DI module is missing: $relativePath"
}

$content = Get-Content -LiteralPath $path -Raw

$forbiddenPatterns = @(
    @{ Pattern = 'CoroutineScope\s*\(\s*SupervisorJob\s*\(\s*\)\s*\+\s*Dispatchers\.IO\s*\)'; Description = 'standalone process CoroutineScope(SupervisorJob() + Dispatchers.IO)' },
    @{ Pattern = '^\s*import\s+kotlinx\.coroutines\.Dispatchers\s*$'; Description = 'Dispatchers import owned only by the shared application scope provider' },
    @{ Pattern = '^\s*import\s+kotlinx\.coroutines\.SupervisorJob\s*$'; Description = 'SupervisorJob import owned only by the shared application scope provider' }
)

foreach ($entry in $forbiddenPatterns) {
    if ([regex]::IsMatch($content, $entry.Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)) {
        throw "Duplicate process async ownership remains in $relativePath: $($entry.Description)."
    }
}

$requiredPatterns = @(
    @{ Pattern = '^\s*import\s+app\.muxtv\.ApplicationIoScope\s*$'; Description = '@ApplicationIoScope import' },
    @{ Pattern = '(?s)fun\s+provideRecentPlaybackObserver\s*\([^)]*@ApplicationIoScope\s+scope\s*:\s*CoroutineScope'; Description = '@ApplicationIoScope CoroutineScope provider parameter' },
    @{ Pattern = 'scope\s*=\s*scope\s*,'; Description = 'RecentPlaybackObserver shared scope wiring' }
)

foreach ($entry in $requiredPatterns) {
    if (-not [regex]::IsMatch($content, $entry.Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)) {
        throw "Process async ownership contract missing in $relativePath: $($entry.Description)."
    }
}

Write-Host "Process async ownership contract passed."
