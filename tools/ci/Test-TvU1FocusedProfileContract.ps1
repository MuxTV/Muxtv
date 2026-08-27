[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Get-RepositoryFileContent {
    param([Parameter(Mandatory)][string]$RelativePath)
    $path = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "U1 focused-profile CI contract input was not found: $RelativePath"
    }
    Get-Content -LiteralPath $path -Raw -Encoding utf8
}

function Assert-ContainsOrdinal {
    param([Parameter(Mandatory)][string]$Content, [Parameter(Mandatory)][string]$Token, [Parameter(Mandatory)][string]$Message)
    if ($Content.IndexOf($Token, [System.StringComparison]::Ordinal) -lt 0) { throw $Message }
}

function Assert-NotContainsOrdinal {
    param([Parameter(Mandatory)][string]$Content, [Parameter(Mandatory)][string]$Token, [Parameter(Mandatory)][string]$Message)
    if ($Content.IndexOf($Token, [System.StringComparison]::Ordinal) -ge 0) { throw $Message }
}

$focusedWorkflow = Get-RepositoryFileContent ".github\workflows\android-tv-focused-device.yml"
$productMatrix = Get-RepositoryFileContent ".github\workflows\android-tv-product-device-matrix.yml"
$productEntrypoint = Get-RepositoryFileContent "tools\ci\Run-HostedAndroidProductTests.sh"
$u1Entrypoint = Get-RepositoryFileContent "tools\ci\Run-HostedTvU1ShellTests.sh"

foreach ($token in @(
    'tools/ci/Run-HostedTvU1ShellTests.sh',
    'script: bash ./tools/ci/Run-HostedTvU1ShellTests.sh',
    'avd-name: MuxTV_TV_CURRENT_API36',
    'api-level: 36'
)) {
    Assert-ContainsOrdinal -Content $focusedWorkflow -Token $token -Message "Focused Android TV workflow is missing isolated U1 profile token: $token"
}

foreach ($token in @(
    'MuxTV_TV_CURRENT_API36',
    'bash ./tools/ci/Run-HostedAndroidProductTests.sh',
    'app.muxtv.RailNavigationJourneyTest',
    '1920x1080',
    '1280x720',
    '213',
    '320',
    'wm size',
    'wm density',
    'OK \\([0-9]+ tests?\\)',
    'FAILURES!!!',
    'profile-result.json'
)) {
    Assert-ContainsOrdinal -Content $u1Entrypoint -Token $token -Message "U1 focused-profile entrypoint is missing fail-closed token: $token"
}

foreach ($token in @('MUXTV_REPRESENTATIVE_TV_PROFILE_GATE', 'RailNavigationJourneyTest', '1280x720')) {
    Assert-NotContainsOrdinal -Content $productEntrypoint -Token $token -Message "Generic Android product entrypoint still owns U1-only profile behavior: $token"
}
Assert-NotContainsOrdinal -Content $productMatrix -Token 'Run-HostedTvU1ShellTests.sh' -Message 'Generic Android product matrix must not own the U1 focused-profile entrypoint.'

Write-Host "U1 representative TV profile execution is isolated to the focused API36 lane."
