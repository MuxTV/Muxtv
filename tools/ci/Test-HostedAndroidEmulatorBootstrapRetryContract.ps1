[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Read-RepositoryFile {
    param([Parameter(Mandatory)][string]$RelativePath)
    $path = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Hosted Android bootstrap retry contract input was not found: $RelativePath"
    }
    Get-Content -LiteralPath $path -Raw -Encoding utf8
}

function Assert-Contains {
    param([Parameter(Mandatory)][string]$Content, [Parameter(Mandatory)][string]$Token, [Parameter(Mandatory)][string]$Message)
    if ($Content.IndexOf($Token, [System.StringComparison]::Ordinal) -lt 0) { throw $Message }
}

function Assert-NotContains {
    param([Parameter(Mandatory)][string]$Content, [Parameter(Mandatory)][string]$Token, [Parameter(Mandatory)][string]$Message)
    if ($Content.IndexOf($Token, [System.StringComparison]::Ordinal) -ge 0) { throw $Message }
}

$wrapper = Read-RepositoryFile ".github\actions\run-hosted-android-tv\action.yml"
$attemptScript = Read-RepositoryFile "tools\ci\Run-HostedAndroidAttempt.sh"

foreach ($token in @(
    'ReactiveCircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d',
    'id: primary',
    'id: retry',
    'continue-on-error: true',
    'retry_required',
    'mux-tv-script-started.marker',
    'Run-HostedAndroidAttempt.sh',
    'steps.primary.outcome',
    'steps.retry.outcome'
)) {
    Assert-Contains -Content $wrapper -Token $token -Message "Hosted Android wrapper is missing bootstrap retry contract token: $token"
}

$runnerMatches = [regex]::Matches(
    $wrapper,
    'ReactiveCircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d'
)
if ($runnerMatches.Count -ne 2) {
    throw "Hosted Android wrapper must contain exactly one primary and one bounded retry runner invocation; found $($runnerMatches.Count)."
}

foreach ($token in @(
    '#!/usr/bin/env bash',
    'set -euo pipefail',
    'mux-tv-script-started.marker',
    'entrypoint',
    'post_entrypoint'
)) {
    Assert-Contains -Content $attemptScript -Token $token -Message "Hosted Android attempt marker script is missing token: $token"
}
Assert-NotContains -Content $attemptScript -Token 'eval ' -Message 'Hosted Android attempt script must not eval workflow-provided commands.'

$workflows = @(
    @{ Path = '.github\workflows\android-tv-focused-device.yml'; Entrypoint = 'Run-HostedAndroidProductTests.sh' },
    @{ Path = '.github\workflows\android-tv-product-device-matrix.yml'; Entrypoint = 'Run-HostedAndroidProductTests.sh' },
    @{ Path = '.github\workflows\integration-gate.yml'; Entrypoint = 'Run-HostedAndroidProductTests.sh' },
    @{ Path = '.github\workflows\database-migration-device-matrix.yml'; Entrypoint = 'Run-HostedDatabaseMigrationTests.sh' },
    @{ Path = '.github\workflows\benchmark-foundation.yml'; Entrypoint = 'Run-HostedMacrobenchmarkDryRun.sh' }
)

foreach ($workflow in $workflows) {
    $content = Read-RepositoryFile $workflow.Path
    Assert-Contains -Content $content -Token 'uses: ./.github/actions/run-hosted-android-tv' -Message "$($workflow.Path) must use the repository-owned hosted Android wrapper."
    Assert-Contains -Content $content -Token "entrypoint: $($workflow.Entrypoint)" -Message "$($workflow.Path) is missing its checked-in hosted Android entrypoint."
    Assert-NotContains -Content $content -Token 'uses: ReactiveCircus/android-emulator-runner@' -Message "$($workflow.Path) must not bypass the bounded bootstrap retry wrapper."
}

Write-Host 'Hosted Android emulator bootstrap retry contract passed.'
